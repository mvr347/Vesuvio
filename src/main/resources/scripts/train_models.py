#!/usr/bin/env python3
"""
Vesuvio automatic ONNX model trainer.

Invoked automatically by ModelAutoTrainer (net.lovelace.vesuvio.check.onnx.ModelAutoTrainer) on
a schedule - not meant to be a polished ML pipeline, just a reliable, dependency-light way to
turn the plugin's continuously-growing labeled dataset into real click_model.onnx / aim_model.onnx
files that MLManager can hot-reload, with zero manual steps once scikit-learn + skl2onnx are
installed.

Reads the dataset CSV produced by DatasetManager (auto_dataset.csv, or any CSV exported via
/vesuvio dataset export - same format):

    uuid,playerName,label,timestamp,reviewer,f0,f1,...,f15,domain

Trains one binary logistic-regression classifier per requested --domains value ("click"/"aim"),
using columns f0.. as the feature vector - all 16 (f0..f15) for "click" (ClickFeatureExtractor's
full layout), only the first 8 (f0..f7) for "aim" (AimFeatureExtractor only ever populates that
many; MLManager truncates aim feature vectors to 8 before inference, so the exported model's
input width MUST match - see DOMAIN_FEATURE_COUNT below) - and `label` (1 = cheat, 0 = legit) as
the target. Exports each model as <domain>_model.onnx with input name "float_input" and a
(output_label, output_probability) output pair - matching what MLManager.evaluateAsync expects
(it reads the *last* output tensor as a [-1, 2] probability matrix).

Usage:
    python3 train_models.py --dataset auto_dataset.csv --output-dir staging --domains click,aim

Exit code 0 on success (all requested domains trained and written). Non-zero on any failure,
with a human-readable message on stderr - ModelAutoTrainer logs this verbatim so a server
operator can see exactly what went wrong (usually: missing dependencies, or not enough data).
"""

import argparse
import csv
import sys
from pathlib import Path

FEATURE_COUNT = 16

# MLManager.evaluateAsync truncates aim feature vectors to the first 8 columns before running
# inference (AimFeatureExtractor only ever populates f0..f7 - meanYaw/meanPitch/varYaw/varPitch/
# snapRatio/zeroRatio/jerk/gcdConsistency - the rest of the 16-wide array is always zero padding).
# The exported model's input width MUST match that truncated width or ONNX Runtime throws a
# shape-mismatch error on every single inference call. Click features use the full 16.
DOMAIN_FEATURE_COUNT = {
    "click": 16,
    "aim": 8,
}


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    sys.exit(1)


def load_dependencies():
    """Imports third-party deps lazily so argparse/--help work even without them installed,
    and so a missing-dependency failure produces one clear message instead of a raw traceback."""
    try:
        import numpy as np  # noqa: F401
    except ImportError:
        fail("numpy is not installed. Run: pip install -r requirements.txt")
    try:
        from sklearn.linear_model import LogisticRegression  # noqa: F401
        from sklearn.model_selection import train_test_split  # noqa: F401
        from sklearn.metrics import accuracy_score, precision_score, recall_score  # noqa: F401
    except ImportError:
        fail("scikit-learn is not installed. Run: pip install -r requirements.txt")
    try:
        from skl2onnx import convert_sklearn  # noqa: F401
        from skl2onnx.common.data_types import FloatTensorType  # noqa: F401
    except ImportError:
        fail("skl2onnx is not installed. Run: pip install -r requirements.txt")

    import numpy as np
    from sklearn.linear_model import LogisticRegression
    from sklearn.model_selection import train_test_split
    from sklearn.metrics import accuracy_score, precision_score, recall_score
    from skl2onnx import convert_sklearn
    from skl2onnx.common.data_types import FloatTensorType

    return {
        "np": np,
        "LogisticRegression": LogisticRegression,
        "train_test_split": train_test_split,
        "accuracy_score": accuracy_score,
        "precision_score": precision_score,
        "recall_score": recall_score,
        "convert_sklearn": convert_sklearn,
        "FloatTensorType": FloatTensorType,
    }


def load_dataset(dataset_path: Path, domain: str, feature_count: int):
    features = []
    labels = []

    with dataset_path.open("r", encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            if row.get("domain") != domain:
                continue
            try:
                label = int(row["label"])
                feats = [float(row[f"f{i}"]) for i in range(feature_count)]
            except (KeyError, ValueError):
                continue  # skip malformed row rather than aborting the whole run
            features.append(feats)
            labels.append(label)

    return features, labels


def train_domain(domain: str, dataset_path: Path, output_dir: Path, deps) -> bool:
    np = deps["np"]
    LogisticRegression = deps["LogisticRegression"]
    train_test_split = deps["train_test_split"]
    accuracy_score = deps["accuracy_score"]
    precision_score = deps["precision_score"]
    recall_score = deps["recall_score"]
    convert_sklearn = deps["convert_sklearn"]
    FloatTensorType = deps["FloatTensorType"]

    feature_count = DOMAIN_FEATURE_COUNT.get(domain, FEATURE_COUNT)
    features, labels = load_dataset(dataset_path, domain, feature_count)
    n_cheat = sum(1 for l in labels if l == 1)
    n_legit = sum(1 for l in labels if l == 0)
    print(f"[{domain}] loaded {len(features)} samples ({n_cheat} cheat, {n_legit} legit)")

    if n_cheat < 5 or n_legit < 5:
        print(f"[{domain}] not enough samples of both classes to train a meaningful model, skipping")
        return False

    X = np.array(features, dtype=np.float32)
    y = np.array(labels, dtype=np.int64)

    try:
        X_train, X_test, y_train, y_test = train_test_split(
            X, y, test_size=0.2, random_state=42, stratify=y
        )
    except ValueError:
        # Too few samples in the minority class to stratify - fall back to training on everything
        # and skipping the held-out evaluation rather than failing the whole run.
        X_train, y_train = X, y
        X_test, y_test = None, None

    model = LogisticRegression(class_weight="balanced", max_iter=2000)
    model.fit(X_train, y_train)

    if X_test is not None and len(X_test) > 0:
        preds = model.predict(X_test)
        acc = accuracy_score(y_test, preds)
        prec = precision_score(y_test, preds, zero_division=0)
        rec = recall_score(y_test, preds, zero_division=0)
        print(f"[{domain}] holdout accuracy={acc:.3f} precision={prec:.3f} recall={rec:.3f}")

    onnx_model = convert_sklearn(
        model,
        initial_types=[("float_input", FloatTensorType([None, feature_count]))],
        options={id(model): {"zipmap": False}},
        target_opset=12,
    )

    output_dir.mkdir(parents=True, exist_ok=True)
    output_path = output_dir / f"{domain}_model.onnx"
    with output_path.open("wb") as f:
        f.write(onnx_model.SerializeToString())
    print(f"[{domain}] wrote {output_path}")
    return True


def main() -> None:
    parser = argparse.ArgumentParser(description="Train Vesuvio ONNX models from the auto-collected dataset.")
    parser.add_argument("--dataset", required=True, type=Path, help="Path to the dataset CSV.")
    parser.add_argument("--output-dir", required=True, type=Path, help="Directory to write <domain>_model.onnx into.")
    parser.add_argument("--domains", required=True, help="Comma-separated domains to train (click,aim).")
    args = parser.parse_args()

    if not args.dataset.exists():
        fail(f"dataset file not found: {args.dataset}")

    deps = load_dependencies()

    domains = [d.strip() for d in args.domains.split(",") if d.strip()]
    if not domains:
        fail("--domains produced an empty list")

    any_trained = False
    for domain in domains:
        if train_domain(domain, args.dataset, args.output_dir, deps):
            any_trained = True

    if not any_trained:
        fail("no domain had enough data to train - see per-domain messages above")


if __name__ == "__main__":
    main()

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

Trains one binary classifier per requested --domains value ("click"/"aim"), using columns f0.. as
the feature vector - all 16 (f0..f15) for "click" (ClickFeatureExtractor's full layout), only the
first 8 (f0..f7) for "aim" (AimFeatureExtractor only ever populates that many; MLManager truncates
aim feature vectors to 8 before inference, so the exported model's input width MUST match - see
DOMAIN_FEATURE_COUNT below) - and `label` (1 = cheat, 0 = legit) as the target. Exports each model
as <domain>_model.onnx with input name "float_input" and a (output_label, output_probability)
output pair - matching what MLManager.evaluateAsync expects (it reads the *last* output tensor as
a [-1, 2] probability matrix).

Model type is selectable via --model-type:
  - "logistic" (default, backward compatible): plain logistic regression.
  - "histgb": HistGradientBoostingClassifier - a nonlinear, monotonicity-free model that can pick
    up feature interactions (e.g. "low variance AND high duplicate ratio together" rather than
    each pushing the decision independently) that a linear model structurally cannot represent.
  - "mlp": a small single-hidden-layer MLPClassifier, for the same reason with a different
    inductive bias - useful to compare against histgb on a given dataset.
Every model type is wrapped in a scikit-learn Pipeline with a RobustScaler (median/IQR-based,
resistant to the extreme outliers a cheat's own feature vectors often are) ahead of the classifier.
skl2onnx converts the whole Pipeline - scaler included - into one ONNX graph, so the scaling
happens inside the exported model and the Java side keeps sending raw, un-normalized features
exactly as before; MLManager needs no changes for this.

Usage:
    python3 train_models.py --dataset auto_dataset.csv --output-dir staging --domains click,aim
    python3 train_models.py --dataset auto_dataset.csv --output-dir staging --domains click --model-type histgb

Exit code 0 on success (all requested domains trained and written). Non-zero on any failure,
with a human-readable message on stderr - ModelAutoTrainer logs this verbatim so a server
operator can see exactly what went wrong (usually: missing dependencies, or not enough data).
"""

import argparse
import csv
import json
import sys
from pathlib import Path

FEATURE_COUNT = 20

# Label sources that carry information the detection pipeline did not already have. BAN and
# TRUSTED labels are self-confirming - the pipeline decided them with the very models being
# trained - so a dataset made only of those teaches the models to reproduce their current opinion,
# mistakes included. See DatasetManager.LabelSource.
EXTERNAL_SOURCES = {"STAFF", "TRAP"}

# MLManager.evaluateAsync truncates aim feature vectors to the first 8 columns before running
# inference (AimFeatureExtractor only ever populates f0..f7 - meanYaw/meanPitch/varYaw/varPitch/
# snapRatio/zeroRatio/jerk/gcdConsistency - the rest of the 16-wide array is always zero padding).
# The exported model's input width MUST match that truncated width or ONNX Runtime throws a
# shape-mismatch error on every single inference call. Click features use the full 16.
DOMAIN_FEATURE_COUNT = {
    "click": 20,
    "aim": 16,
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
        from sklearn.ensemble import HistGradientBoostingClassifier  # noqa: F401
        from sklearn.neural_network import MLPClassifier  # noqa: F401
        from sklearn.preprocessing import RobustScaler  # noqa: F401
        from sklearn.pipeline import Pipeline  # noqa: F401
        from sklearn.model_selection import train_test_split, GroupShuffleSplit  # noqa: F401
        from sklearn.metrics import (  # noqa: F401
            accuracy_score, precision_score, recall_score, average_precision_score,
        )
    except ImportError:
        fail("scikit-learn is not installed. Run: pip install -r requirements.txt")
    try:
        from skl2onnx import convert_sklearn  # noqa: F401
        from skl2onnx.common.data_types import FloatTensorType  # noqa: F401
    except ImportError:
        fail("skl2onnx is not installed. Run: pip install -r requirements.txt")

    import numpy as np
    from sklearn.linear_model import LogisticRegression
    from sklearn.ensemble import HistGradientBoostingClassifier
    from sklearn.neural_network import MLPClassifier
    from sklearn.preprocessing import RobustScaler
    from sklearn.pipeline import Pipeline
    from sklearn.model_selection import train_test_split, GroupShuffleSplit
    from sklearn.metrics import (
        accuracy_score, precision_score, recall_score, average_precision_score,
    )
    from skl2onnx import convert_sklearn
    from skl2onnx.common.data_types import FloatTensorType

    return {
        "np": np,
        "LogisticRegression": LogisticRegression,
        "HistGradientBoostingClassifier": HistGradientBoostingClassifier,
        "MLPClassifier": MLPClassifier,
        "RobustScaler": RobustScaler,
        "Pipeline": Pipeline,
        "train_test_split": train_test_split,
        "GroupShuffleSplit": GroupShuffleSplit,
        "accuracy_score": accuracy_score,
        "precision_score": precision_score,
        "recall_score": recall_score,
        "average_precision_score": average_precision_score,
        "convert_sklearn": convert_sklearn,
        "FloatTensorType": FloatTensorType,
    }


def build_model(model_type: str, deps):
    """Builds the (unfitted) Pipeline for the requested model type. HistGB/MLP are nonlinear and
    can represent feature interactions a single logistic-regression decision boundary cannot;
    logistic stays the default since it is the smallest, fastest, and most battle-tested choice."""
    RobustScaler = deps["RobustScaler"]
    Pipeline = deps["Pipeline"]

    if model_type == "histgb":
        HistGradientBoostingClassifier = deps["HistGradientBoostingClassifier"]
        classifier = HistGradientBoostingClassifier(
            max_iter=150, max_depth=6, learning_rate=0.08,
            l2_regularization=0.1, random_state=42,
        )
    elif model_type == "mlp":
        MLPClassifier = deps["MLPClassifier"]
        classifier = MLPClassifier(
            hidden_layer_sizes=(24,), activation="relu", alpha=1e-3,
            max_iter=800, early_stopping=True, random_state=42,
        )
    elif model_type == "logistic":
        LogisticRegression = deps["LogisticRegression"]
        classifier = LogisticRegression(class_weight="balanced", max_iter=2000)
    else:
        fail(f"unknown --model-type '{model_type}' (expected logistic, histgb, or mlp)")
        return None  # unreachable, keeps type checkers happy

    # RobustScaler (median/IQR) ahead of every model type: cheat feature vectors are frequently
    # extreme outliers themselves (near-zero variance, saturated duplicate ratio), which would
    # otherwise skew a mean/std-based scaler's fitted range using the very data it needs to
    # separate cleanly. skl2onnx bakes the fitted scaler into the exported graph, so the Java side
    # keeps sending raw features unchanged.
    return Pipeline([("scaler", RobustScaler()), ("classifier", classifier)])


def load_dataset(dataset_path: Path, domain: str, feature_count: int):
    """Returns (features, labels, groups, sources).

    `groups` is the player UUID per row. It is what keeps evaluation honest: windows from one
    player are near-duplicates of each other, so a random row split puts the same player on both
    sides and the model scores itself on players it memorised. Splitting by player instead is the
    difference between a believable number and a meaningless one.

    `sources` is the label provenance (see DatasetManager.LabelSource). BAN/TRUSTED labels are
    self-confirming - the pipeline decided them using the very models being trained - whereas
    STAFF/TRAP labels carry outside information, which is why they are held out for validation.
    """
    features, labels, groups, sources = [], [], [], []

    with dataset_path.open("r", encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        # Feature columns are only ever appended, so a file written before the current width is
        # still valid: read what it has and zero-pad the rest. Demanding the full width would
        # silently discard every row collected before the last upgrade - i.e. the entire history.
        available = sum(1 for c in (reader.fieldnames or [])
                        if len(c) > 1 and c[0] == "f" and c[1:].isdigit())
        usable = min(available, feature_count)
        if available and available < feature_count:
            print(f"[{domain}] dataset carries {available} feature columns, model expects "
                  f"{feature_count} - padding the missing ones with zeros")

        for row in reader:
            if row.get("domain") != domain:
                continue
            try:
                label = int(row["label"])
                feats = [float(row[f"f{i}"]) for i in range(usable)]
                feats.extend([0.0] * (feature_count - usable))
            except (KeyError, ValueError):
                continue  # skip malformed row rather than aborting the whole run
            features.append(feats)
            labels.append(label)
            groups.append(row.get("uuid") or "unknown")
            sources.append((row.get("source") or "UNKNOWN").upper())

    return features, labels, groups, sources


def split_by_player(X, y, groups, deps, test_fraction=0.25):
    """Splits so that no player appears on both sides.

    A random row split is what the earlier version did, and it inflates every metric: successive
    feature windows from one player are near-duplicates, so the model is graded on players it has
    already seen and can score ~0.95 while being useless on a new one. Returns None when there are
    too few distinct players to hold any out, which is itself worth reporting rather than hiding.
    """
    np = deps["np"]
    GroupShuffleSplit = deps["GroupShuffleSplit"]

    groups = np.array(groups)
    if len(set(groups.tolist())) < 4:
        return None

    splitter = GroupShuffleSplit(n_splits=1, test_size=test_fraction, random_state=42)
    train_idx, test_idx = next(splitter.split(X, y, groups))
    if len(set(y[test_idx].tolist())) < 2 or len(set(y[train_idx].tolist())) < 2:
        # A split that leaves one side single-class cannot be scored meaningfully.
        return None
    return train_idx, test_idx


def precision_at_max_fpr(y_true, scores, max_fpr, deps):
    """Highest recall reachable while keeping the false-positive rate at or under max_fpr, plus the
    threshold and precision there.

    Accuracy is the wrong yardstick for an anticheat: with 99% legitimate players a model that
    never flags anyone scores 0.99. What matters operationally is how much genuine cheating can be
    caught while almost never punishing an innocent player, so the threshold is chosen against a
    false-positive budget instead.
    """
    np = deps["np"]
    order = np.argsort(-scores)
    y_sorted = np.array(y_true)[order]
    s_sorted = np.array(scores)[order]

    positives = max(1, int((y_sorted == 1).sum()))
    negatives = max(1, int((y_sorted == 0).sum()))

    tp = fp = 0
    best = {"recall": 0.0, "precision": 0.0, "threshold": 1.0, "fpr": 0.0}
    for i in range(len(y_sorted)):
        if y_sorted[i] == 1:
            tp += 1
        else:
            fp += 1
        fpr = fp / negatives
        if fpr > max_fpr:
            break
        recall = tp / positives
        if recall > best["recall"]:
            best = {
                "recall": recall,
                "precision": tp / max(1, tp + fp),
                "threshold": float(s_sorted[i]),
                "fpr": fpr,
            }
    return best


def train_domain(domain: str, dataset_path: Path, output_dir: Path, model_type: str,
                  max_fpr: float, deps) -> bool:
    np = deps["np"]
    average_precision_score = deps["average_precision_score"]
    convert_sklearn = deps["convert_sklearn"]
    FloatTensorType = deps["FloatTensorType"]

    feature_count = DOMAIN_FEATURE_COUNT.get(domain, FEATURE_COUNT)
    features, labels, groups, sources = load_dataset(dataset_path, domain, feature_count)
    n_cheat = sum(1 for l in labels if l == 1)
    n_legit = sum(1 for l in labels if l == 0)
    n_external = sum(1 for s in sources if s in EXTERNAL_SOURCES)
    print(f"[{domain}] loaded {len(features)} samples ({n_cheat} cheat, {n_legit} legit, "
          f"{n_external} externally labelled)")

    if n_cheat < 5 or n_legit < 5:
        print(f"[{domain}] not enough samples of both classes to train a meaningful model, skipping")
        return False

    X = np.array(features, dtype=np.float32)
    y = np.array(labels, dtype=np.int64)

    model = build_model(model_type, deps)
    print(f"[{domain}] training model-type={model_type}")

    report = {
        "domain": domain,
        "model_type": model_type,
        "samples": len(features),
        "cheat": n_cheat,
        "legit": n_legit,
        "external_labels": n_external,
        "max_fpr": max_fpr,
    }

    split = split_by_player(X, y, groups, deps)
    if split is None:
        # Not enough distinct players to hold any out. Train on everything, but say plainly that
        # the model is unvalidated - the Java side refuses to publish on that basis.
        print(f"[{domain}] WARNING: too few distinct players to hold any out - model is UNVALIDATED")
        model.fit(X, y)
        report["validated"] = False
    else:
        train_idx, test_idx = split
        model.fit(X[train_idx], y[train_idx])

        scores = model.predict_proba(X[test_idx])[:, 1]
        y_test = y[test_idx]
        operating = precision_at_max_fpr(y_test, scores, max_fpr, deps)
        pr_auc = float(average_precision_score(y_test, scores))

        n_train_players = len(set(np.array(groups)[train_idx].tolist()))
        n_test_players = len(set(np.array(groups)[test_idx].tolist()))
        print(f"[{domain}] group holdout: {n_train_players} train players / {n_test_players} test players")
        print(f"[{domain}] PR-AUC={pr_auc:.3f} | at FPR<={max_fpr:.4f}: "
              f"recall={operating['recall']:.3f} precision={operating['precision']:.3f} "
              f"threshold={operating['threshold']:.3f}")

        report["validated"] = True
        report["pr_auc"] = pr_auc
        report["recall_at_max_fpr"] = operating["recall"]
        report["precision_at_max_fpr"] = operating["precision"]
        report["suggested_threshold"] = operating["threshold"]
        report["test_players"] = n_test_players
        report["train_players"] = n_train_players

        # Refit on everything for the exported model: the split existed to measure, not to throw
        # away a quarter of the data in the artifact that actually ships.
        model = build_model(model_type, deps)
        model.fit(X, y)

    # zipmap=False on the final classifier step (not the Pipeline id) - skl2onnx keys its options
    # dict by the individual estimator instance, and the classifier is what emits the
    # (label, probabilities) output pair MLManager expects.
    classifier_step = model.named_steps["classifier"]
    try:
        onnx_model = convert_sklearn(
            model,
            initial_types=[("float_input", FloatTensorType([None, feature_count]))],
            options={id(classifier_step): {"zipmap": False}},
            target_opset=12,
        )
    except Exception as exc:
        # Not every model type converts on every scikit-learn/skl2onnx combination - notably
        # HistGradientBoostingClassifier fails on skl2onnx 1.20 with scikit-learn 1.9
        # ("Expected an int, got a boolean"). Report it as a clear, actionable message instead of
        # a raw traceback, and leave the previous live model untouched: silently falling back to a
        # different model type would leave the operator believing they run the one they configured.
        # These converters can dump whole weight arrays into the message; one line is enough to
        # identify the failure, and the rest only buries it in the server log.
        detail = " ".join(str(exc).split())
        if len(detail) > 300:
            detail = detail[:300] + " ..."
        print(f"[{domain}] ERROR: model type '{model_type}' could not be exported to ONNX with the "
              f"installed skl2onnx/scikit-learn: {type(exc).__name__}: {detail}", file=sys.stderr)
        print(f"[{domain}] Use --model-type logistic (or mlp), or upgrade skl2onnx, then retry.",
              file=sys.stderr)
        return False

    output_dir.mkdir(parents=True, exist_ok=True)
    output_path = output_dir / f"{domain}_model.onnx"
    with output_path.open("wb") as f:
        f.write(onnx_model.SerializeToString())

    report_path = output_dir / f"{domain}_report.json"
    with report_path.open("w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)

    print(f"[{domain}] wrote {output_path} and {report_path.name}")
    return True


def main() -> None:
    parser = argparse.ArgumentParser(description="Train Vesuvio ONNX models from the auto-collected dataset.")
    parser.add_argument("--dataset", required=True, type=Path, help="Path to the dataset CSV.")
    parser.add_argument("--output-dir", required=True, type=Path, help="Directory to write <domain>_model.onnx into.")
    parser.add_argument("--domains", required=True, help="Comma-separated domains to train (click,aim).")
    parser.add_argument("--model-type", default="logistic", choices=["logistic", "histgb", "mlp"],
                         help="Classifier type (default: logistic, backward compatible).")
    parser.add_argument("--max-fpr", default=0.001, type=float,
                         help="False-positive budget the operating threshold is chosen against "
                              "(default: 0.001, i.e. 1 in 1000 clean windows).")
    args = parser.parse_args()

    if not args.dataset.exists():
        fail(f"dataset file not found: {args.dataset}")

    deps = load_dependencies()

    domains = [d.strip() for d in args.domains.split(",") if d.strip()]
    if not domains:
        fail("--domains produced an empty list")

    any_trained = False
    for domain in domains:
        if train_domain(domain, args.dataset, args.output_dir, args.model_type, args.max_fpr, deps):
            any_trained = True

    if not any_trained:
        fail("no domain produced a model - see the per-domain messages above "
             "(not enough data, or ONNX export failed)")


if __name__ == "__main__":
    main()

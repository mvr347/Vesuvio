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

    uuid,playerName,label,timestamp,reviewer,f0,f1,...,f19,domain,source,pingMs,tps,clientBrand

Older files are read as they are: the feature width comes from the file's own header and the
trailing columns are optional, so a CSV written by any earlier version still loads.

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

Training samples are weighted by age (--half-life-days): a cheat client from a year ago is a
different program from the one being sold today, so old rows should not outvote recent ones.
--calibrate additionally wraps the classifier in sigmoid calibration, so the probability the model
reports can be read at face value - which is what makes the probability thresholds in config.yml
mean the same thing across a model-type change or a retrain.

The evaluation report (<domain>_report.json) carries calibration quality (Brier, ECE, reliability
bins) and a per-ping-band precision/recall slice, so a model that is only wrong about high-ping
players says so instead of quietly punishing them.

Usage:
    python3 train_models.py --dataset auto_dataset.csv --output-dir staging --domains click,aim
    python3 train_models.py --dataset auto_dataset.csv --output-dir staging --domains click --model-type histgb
    python3 train_models.py --dataset auto_dataset.csv --output-dir staging --domains click --calibrate

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
            brier_score_loss,
        )
        from sklearn.calibration import CalibratedClassifierCV  # noqa: F401
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
        brier_score_loss,
    )
    from sklearn.calibration import CalibratedClassifierCV
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
        "brier_score_loss": brier_score_loss,
        "CalibratedClassifierCV": CalibratedClassifierCV,
        "convert_sklearn": convert_sklearn,
        "FloatTensorType": FloatTensorType,
    }


def build_model(model_type: str, deps, calibrate: bool = False):
    """Builds the (unfitted) Pipeline for the requested model type. HistGB/MLP are nonlinear and
    can represent feature interactions a single logistic-regression decision boundary cannot;
    logistic stays the default since it is the smallest, fastest, and most battle-tested choice."""
    RobustScaler = deps["RobustScaler"]
    Pipeline = deps["Pipeline"]

    if model_type == "histgb":
        HistGradientBoostingClassifier = deps["HistGradientBoostingClassifier"]
        # class_weight balances the loss the same way the logistic model already did. Cheat
        # samples are far rarer than legit ones here, and without it the model minimises loss
        # mostly by agreeing that everyone is legit.
        try:
            classifier = HistGradientBoostingClassifier(
                max_iter=150, max_depth=6, learning_rate=0.08,
                l2_regularization=0.1, random_state=42, class_weight="balanced",
            )
        except TypeError:
            # class_weight landed in scikit-learn 1.2; older versions still work, just unbalanced.
            print("[warn] this scikit-learn is too old for HistGB class_weight - training unbalanced")
            classifier = HistGradientBoostingClassifier(
                max_iter=150, max_depth=6, learning_rate=0.08,
                l2_regularization=0.1, random_state=42,
            )
    elif model_type == "mlp":
        MLPClassifier = deps["MLPClassifier"]
        # MLPClassifier has no class_weight, so class imbalance is not corrected for this model
        # type - one more reason it is not the default. (Per-sample weights it does accept from
        # scikit-learn 1.9 onward, which is how age decay still reaches it - see fit_model.)
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

    if calibrate:
        # Platt/sigmoid calibration on top of the base classifier.
        #
        # Why it matters here specifically: every threshold in config.yml is written as a
        # probability ("flag above 0.85"), and an operator reasonably reads that as "the model is
        # 85% sure". For an uncalibrated model it means nothing of the sort - a boosted tree
        # ensemble in particular pushes its scores toward 0 and 1, so its 0.85 may be closer to a
        # true 0.55, and the same config value behaves completely differently after a model-type
        # change. Calibration makes the number mean what it says, which is what lets the same
        # thresholds survive a retrain.
        #
        # Sigmoid rather than isotonic: isotonic needs far more data to avoid overfitting the
        # calibration curve itself, and an auto-retraining server dataset is usually small.
        CalibratedClassifierCV = deps["CalibratedClassifierCV"]
        classifier = CalibratedClassifierCV(classifier, method="sigmoid", cv=3)

    # RobustScaler (median/IQR) ahead of every model type: cheat feature vectors are frequently
    # extreme outliers themselves (near-zero variance, saturated duplicate ratio), which would
    # otherwise skew a mean/std-based scaler's fitted range using the very data it needs to
    # separate cleanly. skl2onnx bakes the fitted scaler into the exported graph, so the Java side
    # keeps sending raw features unchanged.
    return Pipeline([("scaler", RobustScaler()), ("classifier", classifier)])


def load_dataset(dataset_path: Path, domain: str, feature_count: int):
    """Returns (features, labels, groups, sources, timestamps, contexts).

    `groups` is the player UUID per row. It is what keeps evaluation honest: windows from one
    player are near-duplicates of each other, so a random row split puts the same player on both
    sides and the model scores itself on players it memorised. Splitting by player instead is the
    difference between a believable number and a meaningless one.

    `sources` is the label provenance (see DatasetManager.LabelSource). BAN/TRUSTED labels are
    self-confirming - the pipeline decided them using the very models being trained - whereas
    STAFF/TRAP labels carry outside information, which is why they are held out for validation.

    `timestamps` (epoch millis) drive age-based sample weighting: a cheat client from a year ago
    is a different program from the one being sold today, and weighting old rows as heavily as
    last week's makes the model defend against the past.

    `contexts` are the capture conditions (ping, TPS, client brand - see DatasetManager.
    SampleContext). They are deliberately NOT features: training on ping teaches "distant players
    are cheaters". They are used only to slice the evaluation, so a model whose precision collapses
    above 200ms says so in its report instead of quietly punishing a continent.
    """
    features, labels, groups, sources, timestamps, contexts = [], [], [], [], [], []

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
            timestamps.append(_to_float(row.get("timestamp"), 0.0))
            contexts.append({
                "ping": _to_float(row.get("pingMs"), -1.0),
                "tps": _to_float(row.get("tps"), -1.0),
                "brand": (row.get("clientBrand") or "").strip(),
            })

    return features, labels, groups, sources, timestamps, contexts


def _to_float(raw, fallback: float) -> float:
    try:
        return float(raw)
    except (TypeError, ValueError):
        return fallback


def age_weights(timestamps, half_life_days: float, deps):
    """Exponential decay weights by sample age, or None when weighting is disabled.

    The cheat landscape turns over: a client that was current a year ago has been rewritten, and
    the legitimate playerbase's hardware and habits move too. Without decay, a dataset accumulated
    over a long-lived server is dominated by its own history and the model is tuned against
    software nobody runs any more. A floor keeps old rows contributing something rather than being
    silently deleted - they still describe what cheating looks like in general.
    """
    np = deps["np"]
    if half_life_days <= 0:
        return None

    stamps = np.array(timestamps, dtype=np.float64)
    if not np.any(stamps > 0):
        return None

    newest = float(stamps.max())
    age_days = np.clip((newest - stamps) / 86_400_000.0, 0.0, None)
    weights = np.power(0.5, age_days / half_life_days)
    return np.clip(weights, 0.05, 1.0)


def calibration_metrics(y_true, scores, deps, bins: int = 10):
    """Brier score plus expected calibration error - does a reported 0.9 mean 90%?

    An anticheat's thresholds are all written as probabilities, so a miscalibrated model makes
    every configured threshold mean something other than what the operator read it as. These two
    numbers say whether the probability can be taken at face value: Brier is the mean squared
    error of the probability itself, ECE the average gap between the confidence claimed in a bin
    and the hit rate actually observed in it.
    """
    np = deps["np"]
    brier = float(deps["brier_score_loss"](y_true, scores))

    y_true = np.array(y_true, dtype=np.float64)
    scores = np.array(scores, dtype=np.float64)
    edges = np.linspace(0.0, 1.0, bins + 1)
    ece = 0.0
    reliability = []
    for i in range(bins):
        lo, hi = edges[i], edges[i + 1]
        mask = (scores >= lo) & (scores < hi if i < bins - 1 else scores <= hi)
        count = int(mask.sum())
        if count == 0:
            continue
        confidence = float(scores[mask].mean())
        observed = float(y_true[mask].mean())
        ece += (count / len(scores)) * abs(confidence - observed)
        reliability.append({
            "bin": f"{lo:.1f}-{hi:.1f}", "count": count,
            "mean_predicted": round(confidence, 4), "observed_rate": round(observed, 4),
        })
    return {"brier": round(brier, 5), "ece": round(float(ece), 5), "reliability": reliability}


def slice_by_ping(y_true, scores, contexts, threshold, deps):
    """Precision/recall per ping band on the holdout.

    A model can look excellent overall and still be unusable, because everything it gets wrong is
    concentrated in one band - typically the high-ping players whose click timing is reshaped by
    their connection rather than by any cheat. That failure is invisible in an aggregate number and
    obvious here, so an operator can see it before their distant players do.
    """
    np = deps["np"]
    bands = [(-1, 0, "unknown"), (0, 80, "0-80ms"), (80, 160, "80-160ms"),
             (160, 300, "160-300ms"), (300, 10_000, "300ms+")]
    y_true = np.array(y_true)
    scores = np.array(scores)
    pings = np.array([c.get("ping", -1.0) for c in contexts], dtype=np.float64)

    out = []
    for lo, hi, name in bands:
        mask = (pings < 0) if name == "unknown" else ((pings >= lo) & (pings < hi))
        count = int(mask.sum())
        if count == 0:
            continue
        predicted = scores[mask] >= threshold
        actual = y_true[mask] == 1
        tp = int((predicted & actual).sum())
        fp = int((predicted & ~actual).sum())
        fn = int((~predicted & actual).sum())
        # Undefined rather than zero when the denominator is empty: a band holding only legitimate
        # players has no recall to report, and reporting 0.0 there would read as "the model misses
        # every cheater at this ping" when in truth there were none to catch.
        out.append({
            "band": name, "samples": count,
            "cheat_samples": int(actual.sum()),
            "precision": round(tp / (tp + fp), 4) if (tp + fp) > 0 else None,
            "recall": round(tp / (tp + fn), 4) if (tp + fn) > 0 else None,
            "false_positives": fp,
        })
    return out


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


def fit_model(model, X, y, weights):
    """Fits the pipeline, passing per-sample weights through when the estimator accepts them.

    Which estimators accept `sample_weight` is a moving target across scikit-learn versions
    (MLPClassifier gained it in 1.9, for one), so support is detected from the estimator's own fit
    signature rather than hardcoded per model type - a hardcoded list quietly disables age decay
    the moment it goes out of date, while the report goes on claiming it was applied. The try/except
    covers the remaining case where the signature accepts the argument but the inner estimator
    rejects it at fit time.

    @return whether the weights were actually applied, which is what the report records.
    """
    if weights is None:
        model.fit(X, y)
        return False

    import inspect
    final_step = model.named_steps["classifier"]
    if "sample_weight" not in inspect.signature(final_step.fit).parameters:
        model.fit(X, y)
        return False

    try:
        model.fit(X, y, classifier__sample_weight=weights)
        return True
    except (TypeError, ValueError) as exc:
        print(f"[warn] {type(final_step).__name__} rejected per-sample weights "
              f"({type(exc).__name__}) - training unweighted, age decay not applied")
        model.fit(X, y)
        return False


def train_domain(domain: str, dataset_path: Path, output_dir: Path, model_type: str,
                  max_fpr: float, half_life_days: float, calibrate: bool, deps) -> bool:
    np = deps["np"]
    average_precision_score = deps["average_precision_score"]
    convert_sklearn = deps["convert_sklearn"]
    FloatTensorType = deps["FloatTensorType"]

    feature_count = DOMAIN_FEATURE_COUNT.get(domain, FEATURE_COUNT)
    features, labels, groups, sources, timestamps, contexts = load_dataset(dataset_path, domain, feature_count)
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

    weights = age_weights(timestamps, half_life_days, deps)
    model = build_model(model_type, deps, calibrate)
    print(f"[{domain}] training model-type={model_type}"
          f"{' (sigmoid-calibrated)' if calibrate else ''}")

    report = {
        "domain": domain,
        "model_type": model_type,
        "calibrated": bool(calibrate),
        "samples": len(features),
        "cheat": n_cheat,
        "legit": n_legit,
        "external_labels": n_external,
        "max_fpr": max_fpr,
        "half_life_days": half_life_days,
    }

    split = split_by_player(X, y, groups, deps)
    if split is None:
        # Not enough distinct players to hold any out. Train on everything, but say plainly that
        # the model is unvalidated - the Java side refuses to publish on that basis.
        print(f"[{domain}] WARNING: too few distinct players to hold any out - model is UNVALIDATED")
        weighted = fit_model(model, X, y, weights)
        report["validated"] = False
        report["age_weighted"] = weighted
    else:
        train_idx, test_idx = split
        weighted = fit_model(model, X[train_idx], y[train_idx],
                             None if weights is None else weights[train_idx])
        report["age_weighted"] = weighted

        scores = model.predict_proba(X[test_idx])[:, 1]
        y_test = y[test_idx]
        operating = precision_at_max_fpr(y_test, scores, max_fpr, deps)
        pr_auc = float(average_precision_score(y_test, scores))

        calibration = calibration_metrics(y_test, scores, deps)
        ping_slices = slice_by_ping(y_test, scores, [contexts[i] for i in test_idx],
                                     operating["threshold"], deps)
        report["calibration"] = calibration
        report["ping_slices"] = ping_slices
        print(f"[{domain}] calibration: brier={calibration['brier']:.4f} ece={calibration['ece']:.4f}")
        for sl in ping_slices:
            precision = "n/a" if sl["precision"] is None else f"{sl['precision']:.3f}"
            recall = "n/a" if sl["recall"] is None else f"{sl['recall']:.3f}"
            print(f"[{domain}]   ping {sl['band']}: n={sl['samples']} ({sl['cheat_samples']} cheat) "
                  f"precision={precision} recall={recall} fp={sl['false_positives']}")

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
        model = build_model(model_type, deps, calibrate)
        fit_model(model, X, y, weights)

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
    parser.add_argument("--half-life-days", default=30.0, type=float,
                         help="Age at which a sample counts half as much during training. The cheat "
                              "landscape turns over, so old rows should not outvote recent ones. "
                              "0 disables age weighting entirely (default: 30).")
    parser.add_argument("--calibrate", action="store_true",
                         help="Wrap the classifier in sigmoid (Platt) calibration so its output "
                              "probability can be read at face value - a reported 0.9 really means "
                              "~90%% of such windows are cheats. Recommended with --model-type histgb "
                              "or mlp, whose raw scores are pushed toward 0/1.")
    args = parser.parse_args()

    if not args.dataset.exists():
        fail(f"dataset file not found: {args.dataset}")

    deps = load_dependencies()

    domains = [d.strip() for d in args.domains.split(",") if d.strip()]
    if not domains:
        fail("--domains produced an empty list")

    any_trained = False
    for domain in domains:
        if train_domain(domain, args.dataset, args.output_dir, args.model_type, args.max_fpr,
                        args.half_life_days, args.calibrate, deps):
            any_trained = True

    if not any_trained:
        fail("no domain produced a model - see the per-domain messages above "
             "(not enough data, or ONNX export failed)")


if __name__ == "__main__":
    main()

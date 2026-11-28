import argparse
import collections
import json
from pathlib import Path
import re
import statistics


def distribution(values):
    return {"samples": values, "median": statistics.median(values), "min": min(values), "max": max(values)}


def fields(text):
    return {name: int(value) for name, value in re.findall(r"\b(\w+)=(\d+)", text or "")}


def startup(root):
    samples = json.loads((root / "measurements.json").read_text(encoding="utf-8-sig"))
    groups = collections.defaultdict(list)
    for sample in samples:
        configuration, run, version = sample["Case"].split("-")
        groups[configuration, version].append(sample)
    result = {}
    for (configuration, version), rows in groups.items():
        values = {"wallMs": [row["WallMs"] for row in rows]}
        for row in rows:
            for name, value in fields(row["Metrics"]).items():
                values.setdefault(name, []).append(value)
        result[configuration + "-" + version] = {key: distribution(value) for key, value in values.items()}
    return result


def profile(path):
    events = json.loads(path.read_text(encoding="utf-8-sig"))["recording"]["events"]
    allocations = collections.Counter()
    executions = collections.Counter()
    total_weight = 0
    pauses = []
    configuration = {}
    for event in events:
        values = event["values"]
        kind = event["type"]
        frames = (values.get("stackTrace") or {}).get("frames", [])
        methods = [frame["method"]["type"]["name"] + "." + frame["method"]["name"] for frame in frames]
        project = [method for method in methods if method.startswith("love/crysh/mixinta/")]
        if kind == "jdk.ExecutionSample":
            executions.update(set(project))
        elif kind == "jdk.ObjectAllocationSample":
            total_weight += values["weight"]
            allocations[project[0] if project else "outside MixinTA"] += values["weight"]
        elif kind == "jdk.GarbageCollection":
            pauses.append(float(values["sumOfPauses"][2:-1]) * 1000)
        elif kind in {"jdk.JVMInformation", "jdk.GCConfiguration", "jdk.GCHeapConfiguration"}:
            configuration[kind] = values
    return {
        "eventCounts": dict(collections.Counter(event["type"] for event in events)),
        "allocationWeightBytes": total_weight,
        "allocationSites": dict(allocations.most_common()),
        "inclusiveExecutionSamples": dict(executions.most_common()),
        "gcPausesMs": pauses,
        "configuration": configuration,
    }


def containers(root):
    groups = collections.defaultdict(list)
    for directory in sorted(root.glob("[1-9]*-*")):
        if not directory.is_dir():
            continue
        version = directory.name.split("-", 1)[1]
        for path in sorted(directory.glob("*.metrics")):
            groups[path.stem, version].append(fields(path.read_text(encoding="utf-8-sig")))
    result = {}
    for (case, version), rows in groups.items():
        values = {key: distribution([row[key] for row in rows]) for key in rows[0]}
        result[case + "/" + version] = values
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--startup", type=Path, default=Path("target/performance-comparison"))
    parser.add_argument("--containers", type=Path, default=Path("target/container-performance"))
    parser.add_argument("--heap", type=Path, default=Path("target/container-heap"))
    arguments = parser.parse_args()
    result = {
        "startup": startup(arguments.startup),
        "profiles": {version: profile(arguments.startup / (version + "-events.json")) for version in ["reference", "candidate"]},
        "containers": containers(arguments.containers),
        "cacheStructures": containers(arguments.heap),
    }
    output = arguments.startup / "summary.json"
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(output)


if __name__ == "__main__":
    main()

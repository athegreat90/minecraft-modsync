# Performance evidence

No executable, container or native image has been built in the current environment. No size, startup, memory, CPU, throughput or synchronization figures are claimed.

Targets: native CLI preferably under 40 MB, startup under one second, idle memory under 50 MB. The client has no resident daemon by default. The agent schedules scans, performs hashing sequentially and does no continuous directory reads. It has real resident JVM overhead when running; no zero-idle-resource claim is made.

`python3 scripts/benchmark.py /path/to/modsync` records actual executable bytes, platform and ten process launch measurements for `--help`. It reports unmeasured memory/CPU/download/sync fields as null and states cache/harness limitations. Release jobs attach its JSON per platform. These timings are not full cold-cache or workload benchmarks.

Before release, measure representative small/large modpacks, resident agent idle RSS/CPU, one-shot scans, interrupted retries, native startup, peak RSS, network throughput, download concurrency and total synchronization. Record hardware, OS, architecture, toolchain, artifact digest, workload sizes, warm/cold cache conditions and measurement method. Hash caching is not yet implemented; benchmark the conservative rehashing implementation honestly.

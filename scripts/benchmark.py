#!/usr/bin/env python3
import json, pathlib, platform, statistics, subprocess, sys, time
binary=pathlib.Path(sys.argv[1]).resolve()
timings=[]
for _ in range(10):
    start=time.perf_counter()
    subprocess.run([str(binary),"--help"],stdout=subprocess.DEVNULL,check=True)
    timings.append((time.perf_counter()-start)*1000)
print(json.dumps({"platform":platform.platform(),"architecture":platform.machine(),"bytes":binary.stat().st_size,"command":"--help","firstProcessMs":timings[0],"medianProcessMs":statistics.median(timings),"runs":timings,"peakMemoryBytes":None,"idleMemoryBytes":None,"cpu":None,"downloadThroughput":None,"syncDuration":None,"limitations":"Process launch timings include harness overhead; OS caches were not flushed. Memory and workload benchmarks remain unmeasured."},indent=2))

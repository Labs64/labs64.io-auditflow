"""jfr -> compact stacks file: one line per CPU-time sample, 'thread<TAB>leaf;caller;...;root'."""
import json, re, subprocess, sys
src, dst = sys.argv[1], sys.argv[2]
p = subprocess.run(["jfr", "print", "--json", "--stack-depth", "512", "--events", "jdk.CPUTimeSample", src], capture_output=True, text=True, check=True)
with open(dst, "w") as out:
    for e in json.loads(p.stdout)["recording"]["events"]:
        fr = (e["values"].get("stackTrace") or {}).get("frames")
        if not fr: continue
        st = e["values"].get("eventThread") or {}
        t = re.sub(r"[-#]?\d+$", "", st.get("javaName") or st.get("osName") or "?")
        out.write(t + "\t" + ";".join((f["method"]["type"]["name"] + "." + f["method"]["name"]).replace("/", ".") for f in fr) + "\n")

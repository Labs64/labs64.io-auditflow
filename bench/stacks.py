"""Query a stacks file written by jfr_stacks.py.
  stacks.py FILE layers                 self cost by leaf-most layer
  stacks.py FILE incl PAT...            inclusive share per regex
  stacks.py FILE within PAT             layers of the samples whose stack contains PAT
  stacks.py FILE callers PAT [N]        frame that calls into the first PAT frame
"""
import collections, re, sys
LAYERS = [
    ("logging", r"^(ch\.qos\.logback|net\.logstash|org\.slf4j)"),
    ("observation/metrics", r"^(io\.micrometer|io\.lettuce\.core\.tracing|io\.prometheus)"),
    ("authz client (cerbos/grpc)", r"^(dev\.cerbos|io\.grpc|io\.labs64\.authcontext\.cerbos|com\.google\.protobuf)"),
    ("redis client", r"^(io\.lettuce|org\.springframework\.data\.redis)"),
    ("rabbit client", r"^(com\.rabbitmq|org\.springframework\.amqp|org\.springframework\.cloud\.stream|org\.springframework\.integration|org\.springframework\.cloud\.function|org\.springframework\.messaging)"),
    ("resilience4j", r"^(io\.github\.resilience4j|org\.springframework\.cloud\.circuitbreaker)"),
    ("bean validation", r"^(org\.hibernate\.validator|jakarta\.validation)"),
    ("jackson", r"^(com\.fasterxml\.jackson|tools\.jackson)"),
    ("webclient/reactor-netty", r"^(reactor\.|io\.netty|org\.springframework\.web\.reactive|org\.springframework\.http\.client\.reactive)"),
    ("tomcat/spring-mvc", r"^(org\.apache\.(catalina|coyote|tomcat)|org\.springframework\.web|org\.springframework\.http)"),
    ("spring aop/core", r"^(org\.springframework\.(aop|cglib|core|beans|util|context))"),
    ("auth-context", r"^io\.labs64\.authcontext"),
    ("auditflow", r"^io\.labs64\.audit"),
]
LRE = [(n, re.compile(r)) for n, r in LAYERS]
def layer(frames):
    for f in frames:
        for n, r in LRE:
            if r.search(f): return n
    return "jdk/other"
rows = [(l.split("\t")[0], l.rstrip("\n").split("\t")[1].split(";")) for l in open(sys.argv[1])]
N = len(rows); cmd = sys.argv[2]
def pr(counter, top=20, base=N):
    for k, v in counter.most_common(top): print(f"  {v/base*100:5.1f}%  {k}")
if cmd == "layers":
    pr(collections.Counter(layer(f) for _, f in rows))
elif cmd == "incl":
    for p in sys.argv[3:]:
        r = re.compile(p); print(f"  {sum(1 for _, f in rows if any(r.search(x) for x in f))/N*100:5.1f}%  {p}")
elif cmd == "within":
    r = re.compile(sys.argv[3]); sel = [f for _, f in rows if any(r.search(x) for x in f)]
    print(f"{len(sel)/N*100:.1f}% of samples contain {sys.argv[3]}; their leaf-most layers (% of all):")
    pr(collections.Counter(layer(f) for f in sel))
elif cmd == "callers":
    r = re.compile(sys.argv[3]); c = collections.Counter()
    for _, f in rows:
        idx = [i for i, x in enumerate(f) if r.search(x)]
        if idx:
            i = idx[-1]; c[f"{f[i]}  <-  {f[i+1] if i + 1 < len(f) else '-'}"] += 1
    pr(c, int(sys.argv[4]) if len(sys.argv) > 4 else 15)
if cmd == "bythread":
    g = collections.defaultdict(collections.Counter)
    for t, f in rows: g[t][layer(f)] += 1
    for t, c in sorted(g.items(), key=lambda kv: -sum(kv[1].values()))[:8]:
        print(f"{t}: {sum(c.values())/N*100:.1f}%"); pr(c, 6)

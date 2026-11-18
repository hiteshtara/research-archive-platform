import urllib.request, time
ip = urllib.request.urlopen("https://checkip.amazonaws.com", timeout=20).read().decode().strip()
print("NEG source_ip", ip)
st = {}
for i in range(40):
    try:
        with urllib.request.urlopen("https://api-dev.app-nprd.aws-cloud.bu.edu/actuator/health", timeout=20) as r: s = r.status
    except urllib.error.HTTPError as e: s = e.code
    except Exception as e: s = type(e).__name__
    st[s] = st.get(s, 0) + 1
    time.sleep(0.5)
print("NEG statuses", st, "done_at", time.strftime("%H:%M:%S", time.gmtime()))

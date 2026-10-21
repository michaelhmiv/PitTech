import json, os, time
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError

REPO = "michaelhmiv/PitTech"
HEAD = "9ee2e2d748271ed4f5bb26358dfbde700e5d0680"
BASE = "743b6a5ab0f0b7c952355b26b891d02063e4a732"
RUNS = [39513125120, 39513125322, 39513125321, 39513125091, 39513125045, 39513125256]
report = {"pullRequest": 124, "expectedHead": HEAD, "expectedBase": BASE, "requiredRuns": RUNS, "merged": False}
headers = {"Authorization": "Bearer " + os.environ["GH_TOKEN"], "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"}
def api(path, method="GET", body=None):
    url = "https://api.github.com" + path
    request = Request(url, data=json.dumps(body).encode() if body is not None else None, headers=headers, method=method)
    try:
        with urlopen(request, timeout=25) as response:
            return json.load(response)
    except HTTPError as error:
        raise RuntimeError("GitHub API rejected " + method + " with status " + str(error.code)) from None
def require(value, message):
    if not value:
        raise RuntimeError(message)
def check_run(run_id):
    run = api("/repos/" + REPO + "/actions/runs/" + str(run_id))
    require(run["head_sha"] == HEAD and run["head_branch"] == "work/online-enablement" and run["event"] == "pull_request", "Required run source changed")
    if run["status"] == "completed":
        require(run["conclusion"] == "success", "Required workflow did not pass: " + str(run_id))
        return {"id": run_id, "name": run["name"], "conclusion": run["conclusion"], "attempt": run["run_attempt"]}
    return None
def source_pr():
    pr = api("/repos/" + REPO + "/pulls/124")
    require(pr["head"]["sha"] == HEAD and pr["base"]["ref"] == "main", "Pull request source changed")
    return pr
def perform():
    passed = {}
    last_pending = None
    for attempt in range(160):
        pr = source_pr()
        if pr.get("merged"):
            report.update(merged=True, mergeCommit=pr["merge_commit_sha"], observedAlreadyMerged=True)
            return
        require(pr["state"] == "open" and pr["base"]["sha"] == BASE, "Pull request closed or tested base changed")
        for run_id in RUNS:
            if run_id not in passed:
                result = check_run(run_id)
                if result:
                    passed[run_id] = result
        pending = [value for value in RUNS if value not in passed]
        if pending != last_pending:
            print(json.dumps({"verifiedRuns": list(passed), "waitingForRuns": pending}), flush=True)
            last_pending = pending
        if not pending:
            # Re-read every terminal result immediately before the authorized merge.
            report["verifiedChecks"] = [check_run(run_id) for run_id in RUNS]
            require(all(report["verifiedChecks"]), "A required check started another attempt")
            pr = source_pr()
            require(pr["state"] == "open" and pr["base"]["sha"] == BASE, "Pull request changed before merge")
            require(api("/repos/" + REPO + "/git/ref/heads/main")["object"]["sha"] == BASE, "Main changed after testing")
            if pr["draft"]:
                result = api("/graphql", "POST", {"query": "mutation($id:ID!){markPullRequestReadyForReview(input:{pullRequestId:$id}){pullRequest{isDraft}}}", "variables": {"id": pr["node_id"]}})
                require(not result.get("errors") and result["data"]["markPullRequestReadyForReview"]["pullRequest"]["isDraft"] is False, "Draft transition was not accepted")
            merged = api("/repos/" + REPO + "/pulls/124/merge", "PUT", {"sha": HEAD, "merge_method": "merge", "commit_title": "Prepare verified online runtime and isolated native staging profiles (#124)"})
            require(merged.get("merged") is True, "GitHub did not accept the merge")
            commit = api("/repos/" + REPO + "/git/commits/" + merged["sha"])
            require([parent["sha"] for parent in commit["parents"]] == [BASE, HEAD], "Unexpected merge parents")
            require(api("/repos/" + REPO + "/git/ref/heads/main")["object"]["sha"] == merged["sha"], "Merged main could not be confirmed")
            report.update(merged=True, mergeCommit=merged["sha"], mergedVerifiedCandidate=True)
            print(json.dumps({"merged": True, "mergeCommit": merged["sha"]}), flush=True)
            return
        time.sleep(45)
    raise RuntimeError("Required workflows did not finish inside the bounded wait")
try:
    perform()
except Exception as error:
    report["error"] = str(error)
    raise
finally:
    Path("verified-merge").mkdir(exist_ok=True)
    Path("verified-merge/result.json").write_text(json.dumps(report, indent=2) + "\n")

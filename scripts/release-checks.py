#!/usr/bin/env python3
"""Require successful main CI for this exact release commit, without rerunning it."""
import json
import os
import urllib.parse
import urllib.request


def checked_run(runs, sha, repository):
    return next((run for run in runs if run.get('head_sha') == sha
                 and run.get('head_branch') == 'main' and run.get('event') == 'push'
                 and run.get('status') == 'completed' and run.get('conclusion') == 'success'
                 and run.get('repository', {}).get('full_name') == repository
                 and run.get('path') == '.github/workflows/ci.yml'), None)


def main():
    repository, sha = os.environ['GITHUB_REPOSITORY'], os.environ['GITHUB_SHA']
    query = urllib.parse.urlencode({'head_sha': sha, 'branch': 'main', 'event': 'push',
                                    'status': 'success', 'per_page': 100})
    url = f'https://api.github.com/repos/{repository}/actions/workflows/ci.yml/runs?{query}'
    request = urllib.request.Request(url, headers={
        'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
        'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28'})
    with urllib.request.urlopen(request, timeout=30) as response:
        runs = json.load(response)['workflow_runs']
    run = checked_run(runs, sha, repository)
    if run is None:
        raise SystemExit('No successful main Checks run for this exact commit. Finish CI before tagging; retry this release workflow after CI succeeds.')
    print('Reusing successful main CI: ' + run['html_url'])


if __name__ == '__main__':
    main()

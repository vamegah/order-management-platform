# Grafana — Dashboards & Provisioning

This directory contains Grafana's provisioning configuration and all
version-controlled dashboards.

## Files

- `provisioning/datasources.yml` — Prometheus, Loki, Tempo datasource
  definitions (File 16). Read once at Grafana startup.
- `provisioning/dashboards.yml` — Provider configuration for the OMP
  dashboard folder (File 17). Read once at Grafana startup.
- `dashboards/*.json` — Dashboard definitions. Watched continuously by
  Grafana and reloaded within 30 seconds of any change.

## Directory Structure
infrastructure/grafana/
├── README.md (this file)
├── provisioning/
│ ├── datasources.yml (File 16)
│ └── dashboards.yml (File 17)
└── dashboards/
├── .gitkeep (File 17a)
└── omp-overview.json (File 17c, Phase 0 starting dashboard)

text

## Quick Reference

| I want to... | Do this |
|--------------|---------|
| Add a new dashboard | Export from Grafana UI → save JSON to `dashboards/` → wait 30s |
| Edit a dashboard | Edit the JSON file → wait 30s → refresh Grafana |
| Delete a dashboard | Remove the JSON file → wait 30s |
| Change datasource config | Edit `provisioning/datasources.yml` → `docker compose restart grafana` |
| Change provider config | Edit `provisioning/dashboards.yml` → `docker compose restart grafana` |
| Force a full reload | `docker compose restart grafana` |

## Authoring Workflow (Detailed)

Grafana's UI is excellent for building dashboards; hand-writing panel JSON
is tedious. The recommended workflow is:

1. **Prototype in Grafana.** Dashboards → New → New dashboard. Build your
   panels. Test queries against live data.

2. **Save the prototype.** Give it a temporary name (e.g., "SCRATCH — my
   dashboard"). Save.

3. **Export the JSON.** Dashboard settings (gear) → JSON Model → Copy.

4. **Paste into a file** under `dashboards/`:
infrastructure/grafana/dashboards/omp-<descriptive-name>.json

text

5. **Clean up the JSON.** Manually remove:
- Top-level `"id"` field (Grafana-managed; causes issues on DB reset).
- Any `"iteration"`, `"version"`, or `"schemaVersion"` values you don't
  care about (Grafana overwrites them).

Ensure:
- `"uid"` is set to something stable (e.g., `"omp-orders"`).
- `"title"` is prefixed with `"OMP — "`.
- Every panel's `"datasource"` uses UID, not name:
  ```json
  "datasource": { "type": "prometheus", "uid": "prometheus" }
Save the file. Within 30 seconds, Grafana loads it into the "OMP"
folder. The prototype is still there (in a different folder) — delete it.

Commit. The JSON in git is now the source of truth.

Conventions
Every dashboard JSON should have:

Field	Convention	Example
uid	omp-<kebab-name>, stable	"omp-overview"
title	OMP — <Human Name>	"OMP — Service Overview"
version	Any integer; Grafana uses for change detection	1
tags	["omp"] plus optional category tags	["omp","infrastructure"]
timezone	"browser" for user-local, "utc" for ops dashboards	"utc"
refresh	Auto-refresh interval; "30s" for live views	"30s"
Datasources
Reference datasources by UID (not by name):

Prometheus (metrics): "uid": "prometheus"

Loki (logs): "uid": "loki"

Tempo (traces): "uid": "tempo"

Example panel datasource:

json
"datasource": {
  "type": "prometheus",
  "uid": "prometheus"
}
Wrong (fragile — the name may change):

json
"datasource": "Prometheus"
Template Variables
For consistency, dashboards should define these variables at the top:

$env — environment filter (dev, prod, or .*). Sourced from the
env external label Prometheus attaches (File 15).

$service — service filter. Sourced from the service label on most
metrics.

$instance — instance filter. Optional; sourced from instance.

This makes dashboards portable across environments without editing.

Provisioning Behaviour
Change to...	Requires restart?	Applied within
provisioning/datasources.yml	Yes	Immediately after restart
provisioning/dashboards.yml	Yes	Immediately after restart
dashboards/*.json	No	30 seconds
Restart command:

bash
docker compose restart grafana
Troubleshooting
Dashboard doesn't appear after saving.

Check the file is valid JSON:

bash
jq . infrastructure/grafana/dashboards/omp-<name>.json
Check Grafana sees it:

bash
docker compose logs grafana | grep -i provisioning
Wait 30 seconds (the reload interval).

Look in the "OMP" folder in the sidebar.

"Datasource not found" on panels.

Every panel must reference a datasource by UID. Fix any panel that uses a
name-based datasource.

"No data" but the query looks correct.

Open Explore, run the query manually. If it returns data in Explore but
not on the dashboard, the issue is the panel's time range or variable
substitution.

Two dashboards with the same name.

You have a UI-created prototype and a provisioned one. Delete the UI one.

Edits in the UI don't persist.

Expected — allowUiUpdates: false. Edit the JSON file instead.

Security
Dashboards contain no secrets. Datasource credentials are managed by
Grafana itself (provisioned in File 16) and never appear in dashboard
JSON.

All queries are read-only. Dashboards cannot modify data.

Future Improvements
Dashboard linter: a CI step that validates every JSON file
(schema + datasource UID references).

Export helper: a script that automates the "clean up the JSON"
step of the authoring workflow.

Snapshot testing: render dashboards in CI and compare to expected
PNGs (useful but fragile — deferred).

References
Grafana provisioning: https://grafana.com/docs/grafana/latest/administration/provisioning/

Dashboard JSON model: https://grafana.com/docs/grafana/latest/dashboards/json-model/

Prometheus datasource: https://grafana.com/docs/grafana/latest/datasources/prometheus/
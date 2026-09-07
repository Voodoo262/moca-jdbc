# Screenshots

The images referenced from the root `README.md` live here. They are **placeholders** until
captured — each reference in the README is preceded by an HTML comment describing exactly
what the shot has to show.

| File | Shows |
|---|---|
| `dbeaver-driver-manager-settings.png` | Driver Manager → New → **Settings**: driver name, class name, URL template, default port |
| `dbeaver-driver-manager-libraries.png` | **Libraries** tab: the jar added, driver class resolved |
| `dbeaver-connection-settings.png` | Connection dialog: host, port, user, password |
| `dbeaver-sql-editor.png` | `list warehouses` run in the SQL editor, results grid populated |
| `dbeaver-database-navigator.png` | Navigator tree expanded to show tables |

## Capture conventions

- **This repository is public.** No real hostnames, IP addresses, usernames, passwords or
  customer data may be visible. Use placeholders such as `moca.example.com` / `demo`, and
  check the window title and any recent-connections dropdown before saving — those leak
  more often than the dialog itself.
- Light theme, default DBeaver styling.
- Crop to the dialog or panel in question, not the whole desktop.
- PNG, at 1x or 2x; keep each file under about 300 KB.
- Note the DBeaver version used in the PR that adds them, so they can be spotted as stale
  when the UI changes.

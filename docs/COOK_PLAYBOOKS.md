# Cook playbooks

Completed cooks offer **Save as playbook** and **Cook again**. Both review copied setup and recognized cooking actions. Cooks has a collapsible Playbooks collection for manual playbooks and revisions. Cook again opens the ordinary setup form; **Setup only** is available there.

A playbook copies dish preparation, ingredients, starting setpoint, fuel, selected targets and user-reviewed steps. Recorded temperatures, controller sessions, incidental photos and connection diagnostics remain historical data. Optional milestone photos are copied to independent app-owned files. Revisions get new IDs; an active cook stores its own snapshot and new dish mapping. Editing a saved revision cannot alter an active cook.

Source temperature observations are displayed separately when choosing a step's trigger. They are reference observations, never automatic targets. Temperature inputs honor the preferred unit. Calendar checks use date/time pickers and require new dates when reusing a past reminder. Draft dish ordering is captured explicitly when assigning new cook IDs.

Room v6 adds two small document/attachment tables with cook ownership and foreign keys. Playbook payloads use an explicitly validated versioned domain codec. Archive v5 adds saved-guidance JSON, CSV/workbook tables and reference attachments; older archives omit these optional collections. Restored plans start with guidance paused. No controller credentials or active recording state are exported.

Verification: JVM codec/validation tests, instrumented revision/snapshot/source-deletion/archive round trip, existing upgrade and user flows. Physical controller validation remains separate from manual playbook behavior.

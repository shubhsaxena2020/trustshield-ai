# v3 deduplication and filtering report

- Initial new candidates: 1152
- Holdout text-only filter removed: 4
- After exact/normalized deduplication: 1106
- After v1-validation cosine/template filters: 1086

## Removal counts
- `near_duplicate_validation_cosine_ge_0.60`: 20
- `new_normalized_duplicate`: 38
- `new_normalized_label_conflict`: 4

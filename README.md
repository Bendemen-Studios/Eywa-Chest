# EywaChest

Paper 1.26.3 chest autosell plugin foundation for EywaMC.

## Dependencies
- ExcellentEconomy 2.8.0
- ExcellentShop 5.1.7

## Commands
- `/eywachest give <player> [amount]` — OP, console, or `eywachest.give`
- `/eywachest limit <player> <amount>` and `upgrade <player> <extra>` — OP, console, or `eywachest.chestadmin`
- `/eywachest info`
- `/eywachest trust <player>` / `untrust <player>` — look at the target EywaChest
- `/eywachest currency <id>` — choose per-chest currency
- `/eywachest reload`

Default limit: 2. Double chests count as one. Data persists in plugins/EywaChest/data.yml.

## Permissions
- `eywachest.use` (default true)
- `eywachest.give` (default op)
- `eywachest.chestadmin` (default op)

## Build
Run `mvn -U clean package`.

## Integration status
This initial branch implements chest issuance, limits, trust access, currency selection, persistence and protections. Automatic selling remains disabled until the exact ExcellentShop 5.1.7 price lookup and ExcellentEconomy 2.8.0 deposit API signatures are verified. No items are removed without confirmed payment.

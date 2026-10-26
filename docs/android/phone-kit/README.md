# Phone-ready kit

This folder is prepared ahead of the phone session. Do not run any phone-facing command until the owner confirms USB debugging is connected and releases the phone hold in `../HEAD_DIRECTIVE_PHONE_HOLD.md`.

## Preparation

Generate the printable, synthetic-only zero-amount QR sheet:

```powershell
python docs/android/phone-kit/generate_demo_qr_cards.py
```

The generator uses local Python packages `qrcode[pil]`; it makes four `upi://pay` QR codes with a clearly marked synthetic recipient, zero amount, and no real account. The QR cards are test inputs only. Do not complete or approve a payment.

## Device helpers

All PowerShell helpers require an explicit `-Serial`, verify that the serial is connected and authorized, and do not use network or modify device settings beyond the named action. Logs default to `docs/android/phone-kit/scripts/logs/`, relative to each helper; review before sharing. The collector stores package-specific metadata, memory/frame summaries, and crash/ANR markers only; it does not export general logcat or app data.

```powershell
pwsh -File docs/android/phone-kit/scripts/phone_install.ps1 -Serial <SERIAL>
pwsh -File docs/android/phone-kit/scripts/phone_grant_permissions.ps1 -Serial <SERIAL>
pwsh -File docs/android/phone-kit/scripts/phone_monkey.ps1 -Serial <SERIAL> -Events 2000 -Seeds 4242,7,99
pwsh -File docs/android/phone-kit/scripts/phone_collect_logs.ps1 -Serial <SERIAL>
```

See [PHONE_TEST_PLAN.md](PHONE_TEST_PLAN.md) for the timed checklist and the exact run record.

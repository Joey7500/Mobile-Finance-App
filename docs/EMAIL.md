# Bank e-mails: confirming every payment

Notifications are instant but can be missed (phone off, battery saver, a bank
that does not notify card payments). Bank e-mails arrive about 6 minutes later,
carry the **settled amount**, the **account number** and usually the
**balance**. VaultFlow reads them from a Gmail inbox and fuses everything:

```
Google Wallet  ─┐
Bank app       ─┼─► one transaction per payment ─► budget + account balance
Bank e-mail    ─┘        (sources: Wallet · App · E-mail ✓)
```

- The same payment reported by Wallet, the bank app and the e-mail becomes **one**
  record (amount from the bank, nicest merchant name, earliest time).
- Two identical payments (two 89 Kč coffees) stay **two** records.
- A payment between **your own accounts** (ČS → KB, a card top-up of Revolut)
  is booked as an internal transfer: money leaves one account and appears on
  the other. It does not count as spending.
- When an e-mail states the balance, VaultFlow checks its own figure against
  it. It shows **✓ matches** or **⚠ off by X** (a missed payment) under the
  account, then corrects the balance.

## 1. Create a dedicated Gmail (recommended)

An App Password can read the whole mailbox. Use a separate Gmail that only
receives bank e-mails, e.g. `yourname.banky@gmail.com`, so the app can never
see your personal mail.

## 2. Turn on 2-Step Verification and create an App Password

1. Sign in to the dedicated Gmail on a PC → https://myaccount.google.com/security
2. **2-Step Verification** → turn it on (an SMS or the Google prompt on your phone).
3. Open https://myaccount.google.com/apppasswords → name it `VaultFlow` → **Create**.
4. Copy the 16-letter password. You will only see it once. Don't save it in any
   file: you'll type it straight into the app.

## 3. Send the bank e-mails to that address

You can either set the dedicated address directly in each bank, or forward
from your main Gmail.

**Česká spořitelna (George):** George → Settings → Notifications (Upozornění)
→ e-mail → enter the dedicated address → enable *card payments*, *incoming
and outgoing payments* and the *daily balance*.

**Komerční banka:** KB+ / internet banking → Notifications → e-mail →
incoming and outgoing payments. (KB does not e-mail card payments. Those come
from Wallet / the KB app notification.)

**Forwarding instead:** main Gmail → ⚙ → See all settings → *Forwarding and
POP/IMAP* → *Add a forwarding address* (confirm the code that arrives in the
dedicated inbox). Then create a filter `from:(csas.cz OR kb.cz)` → *Forward to*
the dedicated address. Forwarded mail keeps the bank as sender, which is what
VaultFlow needs.

Leave the e-mails in the **Inbox** (don't let a filter archive them). VaultFlow
reads the Inbox read-only and never deletes, moves or marks anything.

## 4. Connect it in VaultFlow

1. Settings → **Bank e-mails (Gmail)** → enter the address and the App Password → **Test**.
   You should see *Connected ✓*.
2. **Save**. VaultFlow now checks the inbox every 15 minutes, and about 8 minutes
   after every captured payment. **Check now** runs it immediately.
3. Settings → *Apps that sent money notifications*: e-mails from Czech bank
   domains (csas.cz, kb.cz, …) are enabled automatically and appear as
   `✉ address`. Pick **Book to account** for each one.

## 5. Describe your accounts once

Settings → Accounts → tap an account:

| Field | Example | Why |
|---|---|---|
| Account number of THIS account | `1234567890` (prefix and `/bank code` optional) | Recognises the account in e-mails ("z účtu …", "na účet …") and transfers between your accounts |
| Last 4 digits of cards | `1111` | Books card payments to this account |
| Top-up names | `REVOLUT` (on the Revolut account) | A card payment "u Revolut**…" from ČS becomes a transfer into Revolut |
| Overdraft / kontokorent | learned automatically | "k dispozici 5 000 Kč (včetně kontokorentu 1 000 Kč)" means you have 4 000 Kč of your own |

For **Revolut** (notifications only): add the account with its current
balance, set *Top-up names* to `REVOLUT`, and map the Revolut app to it under
*Apps that sent money notifications*.

## How it decides

| Situation | Result |
|---|---|
| Wallet tap → George notification → ČS e-mail (6 min) | 1 transaction, sources *Wallet · App · E-mail ✓* |
| ČS e-mail "z účtu ČS … na účet KB" | −X on ČS, +X expected on KB (internal transfer) |
| KB e-mail "připsána platba z účtu ČS" later | confirms the expected +X, no duplicate |
| ČS card payment "u Revolut**…", Revolut "Money added" | −X on ČS, +X on Revolut, both internal transfer |
| Daily balance e-mail | balance check + correction, no transaction |
| E-mail arrives but the notification never did | the e-mail creates the transaction |

## Privacy

- Only e-mails containing an amount are looked at. Senders you haven't enabled
  are only counted.
- The capture log keeps what happened to each message for 7 days, encrypted.
  You can switch it off.
- The App Password lives in the Android Keystore vault, like the Trading 212 key.
  To revoke access, delete the App Password at myaccount.google.com/apppasswords.

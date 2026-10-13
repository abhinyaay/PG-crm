# Fingerprint entry & exit — setup guide for PG owners

This guide explains, step by step and without technical jargon, how to connect a fingerprint machine at your PG's
gate to the app so that you can see **who came in, who went out, and who is inside right now** — and limit how many
times a resident may come in per day.

The feature is **optional** and **switched off** for every property until you turn it on.

---

## 1. How it works in one minute

* A fingerprint machine is mounted at the gate. Each resident is registered on the machine **under a number**
  (we call it the **Fingerprint ID**; on the machine it is called *User ID* or *PIN*).
* Every time someone puts their finger on the machine, the machine sends a short message to your PG app over the
  internet (or your building's Wi-Fi/LAN): *"Fingerprint ID 12 came in at 21:40"*.
* The app knows that Fingerprint ID 12 is, say, Rahul in room 204, so it records **Rahul — IN — 21:40**.
* **Entry is only ever recorded by the machine.** If the machine is broken, a manager can let a resident in from the
  app, but must type a reason, and this is recorded permanently with the manager's name.
* **Exit** works the way you choose (see section 5): by the machine, by an **"I'm leaving" button** in the resident's
  app, or either.
* **Your residents' fingerprints never leave the machine.** The app only stores the Fingerprint ID number and the
  times people came and went.

---

## 2. What to buy

You need a fingerprint attendance / access terminal that can **send data to a server by itself** ("push"). In India
these are commonly sold as **ZKTeco** or **eSSL** models. Before buying, ask the shop or check the manual:

| Ask / look for | Why it matters |
|---|---|
| **"ADMS"**, **"Cloud Server"**, **"Push"** or **"iClock server"** in the *Communication (COMM)* menu | This is the feature that lets the machine talk to your app directly. **Without it, the machine will not work on its own.** |
| **Wi-Fi or LAN (Ethernet) port** | The machine needs a network connection. A LAN cable to your router is the most reliable. |
| **"HTTPS" / "SSL" option** in the Cloud Server menu (nice to have) | Needed if the machine has to reach your app on the internet securely (section 7-B). If it is missing, use the small bridge described in section 7-C. |
| **Door lock / relay output** (optional) | Only if you want the machine to open an electric lock. |

Tips:

* Ask the seller: *"Does this model support ADMS / Cloud Server push?"* — if they look confused, choose another model.
* Face-recognition and card models with the same menu also work (the app records "face" or "card" instead of
  "finger").
* One machine at the main gate is enough to start. If you have a separate exit gate, buy a second one and register
  it as the "exit" machine.
* Keep the machine's **serial number** handy. It is printed on a sticker on the back/bottom and is also shown in the
  machine's menu under *System Info → Device Info* (looks like `CQZ7232460123` or `AEYU203760012`).

We do not endorse any particular brand or model; any terminal with the ADMS / Cloud Server push menu should work.

---

## 3. Turn the feature on for your property

You need an **owner (property admin)** login.

1. Open the app → **Properties** → choose your property → **Access control** tab.
2. Switch **Enable fingerprint entry/exit** on.
3. Choose the **Exit mode** (explained in section 5). If unsure, choose **Device or button**.
4. Set **Entries allowed per day** (for example `4`), or leave it empty for **unlimited**.
5. Save.

Every change here is recorded in the audit log with your name.

---

## 4. Register the machine in the app

Do this **before** connecting the machine, otherwise the app will refuse its messages.

1. Open **Settings → Biometric devices → Add device**.
2. Fill in:
   * **Property** — where the machine is installed.
   * **Name** — something you will recognise, e.g. "Main gate".
   * **Type** — *ZKTeco (ADMS)* or *eSSL (ADMS)*. Choose *Bridge / other* only for the setup in section 7-C.
   * **Serial number** — exactly as on the sticker (capital/small letters do not matter).
   * **Mounted at** — *Entry*, *Exit*, or *Both* (one machine for coming in and going out; residents choose
     "Check-in"/"Check-out" on the machine's screen, or the machine's function keys).
3. Save. The app now shows:
   * the **serial number** (to double-check),
   * a **device key** — a long code starting with `pgd_`. **It is shown only once.** You only need it for the bridge
     (section 7-C). Copy it somewhere safe; if you lose it, press **Rotate key** to get a new one (the old one stops
     working immediately).
   * the setup addresses to type into the machine (section 7).

---

## 5. Exit mode and entry limit

**Exit mode** — how the app learns that a resident left:

| Mode | What happens | Good for |
|---|---|---|
| **Device** | Only the fingerprint machine records exits. The "I'm leaving" button is hidden. | Gates with an exit machine, or one machine set to *Both*. |
| **Button** | Residents tap **"I'm leaving"** in their app (or a staff member taps it for them). Exit punches from the machine are kept in the log but ignored. | One entry-only machine; residents just tap the button when going out. |
| **Device or button** | Both work. | Most PGs. |

**Entries per day** — "how many times can a person enter per day":

* Set the property default in the Access control tab (empty = unlimited).
* You can give an individual resident a different limit (or "unlimited") on their profile → **Entry limit**. Choosing
  "use property default" removes the personal limit.
* A "day" runs from **midnight to midnight India time**.
* When someone goes over the limit, the entry is recorded as **"Denied – limit exceeded"**, the **property managers get
  an alert** (in the app and by email) and the **resident gets an in-app message**.
* Important: a fingerprint machine that only *pushes* data cannot stop the door from opening — it records and alerts.
  To actually block the door when the limit is reached you need a door controller that asks the app first (the
  "verify" feature — ask your installer about section 7-C).

A **manager** (or owner) can let a resident in from the app with **Manual entry**, e.g. when the machine is broken or
the resident's finger is not recognised. A reason is required and the entry is permanently recorded with the manager's
name. Manual entries are allowed even if the daily limit was used up (the log notes this).

---

## 6. Enrol each resident's fingerprint

For every resident:

1. In the app open the resident → **Fingerprint ID** → **Assign**. The app suggests the next free number (for example
   `12`). Write it down. (Numbers are never reused, so an old resident's fingerprint can never open the door as a new
   resident.)
2. On the machine: **Menu → User Management → New User**.
   * **User ID / PIN**: type **exactly** the Fingerprint ID from the app (`12`).
   * Name: optional (the app already knows the name; you can leave it empty for privacy).
   * **Fingerprint**: let the resident press the same finger 3 times. Enrolling a second finger is a good idea.
   * Save.
3. Ask the resident to try it once. Within a few seconds the entry should appear in the app under **Access control →
   Gate log**.

When a resident checks out (moves out), the app automatically switches off their Fingerprint ID. **Also delete the
user on the machine** (Menu → User Management → find the User ID → Delete) — this removes their fingerprint.

Staff can be enrolled the same way (assign a Fingerprint ID to the staff member). Staff punches are only counted when
**Track staff** is switched on in the Access control tab.

---

## 7. Connect the machine to the app

On the machine open **Menu → COMM (Communication) → Cloud Server Setting** (sometimes *ADMS* or *Server*). First set
the network under **COMM → Ethernet / Wi-Fi** so the machine is on your building's network (DHCP "on" is fine).

Also set the machine's clock: **Menu → System → Date Time** → time zone **UTC+05:30** (India). Many models also
take the time from the server each time they connect, but do not rely on it.

### A. The app runs on a computer in your building (local network)

Use this when the PG software runs on a PC/server in the same building.

| Setting on the machine | Value |
|---|---|
| Enable domain name | **Off** |
| Server address | The **IP address of the computer running the app**, e.g. `192.168.1.20` (ask whoever installed it, or see that computer's network settings) |
| Server port | The port the app is opened on in a browser — usually **80** (if you open it as `http://192.168.1.20:8088`, the port is `8088`) |
| HTTPS / SSL | **Off** |
| Proxy | **Off** |

Tip: give the app's computer a **fixed IP address** in your router, otherwise the address may change after a power
cut and the machine will stop reaching it.

### B. The app is on the internet (you open it like `https://pg.example.com`)

| Setting on the machine | Value |
|---|---|
| Enable domain name | **On** |
| Server address | Your site's domain **without** `https://`, e.g. `pg.example.com` |
| Server port | **443** |
| HTTPS / SSL | **On** |
| Proxy | Off |

Checklist for whoever manages your server/hosting:

* The site must have a valid HTTPS certificate (the same one your browser uses is fine).
* Port **443** must be open to the internet (it already is if you can open the site from your phone on mobile data).
* The path `/iclock/` must be passed through to the app **without login** — the standard setup does this; the app
  only accepts registered serial numbers and limits how fast unknown machines can call.
* The machine needs internet access from your building (your normal broadband is fine). No port needs to be opened on
  your **building's** router — the machine always calls out.

If your machine's menu has **no HTTPS option**, do **not** set port 80 against an internet site; use the bridge (C).

### C. Older machines, or machines without HTTPS → the bridge

Some older machines can only be read from a computer in the same building, or can only use plain HTTP. In that case a
small **bridge** program runs on any always-on computer or Raspberry Pi in the building. It collects punches from the
machine over the local network and forwards them securely to the app using the **device key** from step 4.

* Register the machine with **Type = Bridge / other** (or keep the ZKTeco/eSSL type — both work with a bridge).
* Give your installer the **device key** and these addresses (replace the domain with yours):
  * send punches: `https://pg.example.com/api/v1/access/device-events` (header `X-Device-Key: <key>`)
  * ask before opening the door (door controllers): `https://pg.example.com/api/v1/access/verify`
* The bridge can safely re-send the same punches; the app stores each punch only once.
* If the key is ever leaked, press **Rotate key** and update the bridge.

---

## 8. Test it (with or without a machine)

**With the machine:** after setting the Cloud Server address, the machine shows a small cloud/server icon on its
screen when connected. In the app, **Settings → Biometric devices** shows the machine as **Online** with "last seen"
a few seconds ago. Put an enrolled finger → the entry appears in **Gate log** and the person in **Who's inside**.

**Without a machine (simulator):** whoever runs your server can pretend to be a machine. First register a made-up
serial number (e.g. `TEST0001`) in the app, assign Fingerprint IDs `1` and `2` to two residents, then run:

```bash
# local installation
python3 scripts/biometric-simulator.py --url http://localhost --serial TEST0001 --punch 1:in --punch 2:in --punch 2:out --resend

# site on the internet
python3 scripts/biometric-simulator.py --url https://pg.example.com --serial TEST0001 --punch 1:in --resend
```

It shows each step (connect, upload, re-send, heartbeat). The re-send demonstrates that duplicate punches are stored
only once. To see the daily limit in action, set the limit to 2 and send `--punch 1:in` three times in one run:
`--punch 1:in --punch 1:in --punch 1:in`. Run `python3 scripts/biometric-simulator.py --help` for all options.

---

## 9. Troubleshooting

| Problem | What to check |
|---|---|
| **Machine shows "offline"** in the app, or managers got a *"Gate device offline"* alert | Power and network cable/Wi-Fi of the machine; the Cloud Server address/port (section 7); for (A) that the app's computer is on and its IP did not change; for (B) that the building has internet. The alert is sent once per outage; it clears itself when the machine reconnects. The offline time limit can be changed in the Access control tab (default 15 minutes). |
| **Nothing arrives, and the machine was never online** | The serial number registered in the app must match the machine exactly (System Info → Device Info). Machines that are not registered, deleted or disabled are rejected. Check "Enable domain name" is on only when you typed a domain, and HTTPS matches the port (443 = on, 80 = off). |
| **Times in the log are wrong** (e.g. 5½ hours off) | Set the machine's time zone to UTC+05:30 and the correct date/time (Menu → System → Date Time). If the machine is set to another time zone on purpose, tell the app under the device's settings (*Time zone*). Entries far in the future are marked "device clock ahead". |
| **"Unknown fingerprint" / UNKNOWN_CREDENTIAL in the log** | The User ID typed on the machine has no Fingerprint ID in the app (or it was switched off). Assign the same number in the app, or re-enrol the person on the machine with the number the app shows. |
| **"Not a resident"** in the log | The person is not checked in at this property (moved out, not yet checked in, or staff while *Track staff* is off). |
| **"Denied – limit exceeded"** | The resident used up today's entries. Raise their personal limit (resident → Entry limit), or let them in with **Manual entry** and a reason. The count restarts at midnight India time. |
| **"Ignored (exit mode)"** | The property is in **Button** exit mode, so the machine's exit punches are not counted. Change the exit mode or ask residents to use the button. |
| **"Access control is off"** entries | The machine is sending, but the feature is switched off for the property. Nothing is lost — turn it on in the Access control tab. |
| **Resident cannot see the "I'm leaving" button** | The exit mode is *Device*, the feature is off, or they are not checked in. |

---

## 10. Privacy notes (India DPDP Act friendly)

* **Fingerprints stay on the machine.** The app never receives fingerprint images or templates — only the Fingerprint
  ID number and the time of each entry/exit.
* Tell residents (for example in the house rules or agreement) that entry/exit times are recorded for safety and
  that they can ask to see their own record — residents see their own log in their app.
* Only owners, managers and staff of that property can see the gate log; residents only see their own entries.
* When a resident moves out, their Fingerprint ID is switched off automatically. **Delete their user on the machine**
  as well, so their fingerprint is erased.
* Device keys are stored in a scrambled (hashed) form and shown only once.
* Settings changes, device registration, Fingerprint ID changes, limit changes, staff exits on behalf of residents and
  manual entries are all recorded in the audit log.

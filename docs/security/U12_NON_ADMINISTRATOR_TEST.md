# Security Admin visibility check — tester instructions

**For:** Lalitha (lhuval@bu.edu)
**Where:** the DEV Research Archive — https://nonprod.kuali-archive.bu.edu/
**Time:** about five minutes
**What we're checking:** that the new Security Administration area is
invisible and unreachable for someone who is not an archive administrator.

You are not expected to find anything wrong. If everything below behaves as
described, that **is** the result we need.

Nothing you do here changes any account, permission or data. Every step is
read-only.

---

## 1. Sign in

Go to **https://nonprod.kuali-archive.bu.edu/** and sign in with your BU
login, completing Duo as usual.

- [ ] Sign-in completes and you reach the archive

## 2. Check the normal archive still works

Click through the left-hand menu: **Dashboard, Awards, Proposals,
Negotiations, Subawards, Global Search**.

- [ ] Each page opens and shows content (search boxes, counts, headings)
- [ ] Nothing is blank, and no error message appears

## 3. Look at the top-right of the header

Next to your name there should be **nothing** resembling a mode switch.

Specifically, you should **not** see a pair of buttons labelled
**"Research Archive"** and **"Security Admin"**.

- [ ] No "Security Admin" button anywhere in the header
- [ ] No "Security Administration", "Identities", "Administrators",
      "Permissions" or "Audit Log" entries in the left-hand menu

## 4. Try the administrator address directly

Type this into the address bar and press Enter:

```
https://nonprod.kuali-archive.bu.edu/admin/overview
```

- [ ] You see a short message saying administrator access is required
- [ ] There is **no** search box, button, form or list of people on that page
- [ ] Nothing offers to let you look anybody up

Then press the browser **Back** button.

- [ ] You return to the normal archive and the message is gone

---

## What to send back

Just the checkboxes, plus a note on anything that did not match. A
screenshot of the header (step 3) and of the `/admin/overview` page
(step 4) is helpful if you can.

**Please say explicitly whether you saw a "Security Admin" button.**
That one answer is the point of the whole exercise.

---

## If you DO see a "Security Admin" button

That is not a bug, and nothing is broken. It would mean your DEV identity
holds the archive administrator role, which would make you the wrong person
for this particular check. Stop at that point and say so — we would just
need a different tester.

## If something else looks wrong

Note what you saw and stop. Please don't retry repeatedly or try to work
around it; an accurate description of the first failure is more useful.

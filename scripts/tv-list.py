#!/usr/bin/env python3
"""The TV list for the Pages site (site/tvs/): what the app does on each TV it has seen.

Builds one row per TV (brand, model and codename) from three inputs, models and statuses only:

  1. The app's tested lists: core/spec/src/main/resources/tv/cleanyour/core/spec/profile-meta.json
     of the release, and core/spec/candidates/candidate-meta.json (the lists of the test build).
  2. The release scan of the TV reports: a TSV with one row per issue, the newest report run
     through the release's own home screen rule (columns brand, manufacturer, model, device,
     android, res, b046final, r0Blocker, heldOut, resolved, claimants). b046final is the release's
     HomeBlock; NONE means the switch is planned. A scan from an earlier release can be reused: the
     release's own rule is read from the checkout (below) and laid over it.
  3. site/tvs/coming-soon.json, kept by hand: what the next versions bring, with match rules.
  4. Optionally the confirmations table the issue routine keeps (-k, research/release-0.4.7/
     confirmations.json, from scripts/triage/confirm.py on the triage-bot branch): for each brand and
     Android version, how many testers confirmed the new home screen stayed after a restart, and how
     many the graduation rule needs (2 per brand and Android version since 2026-10-29; it was 3 on a version not yet proven). Next
     test build TVs, and Coming soon TVs waiting for that count, show "N of M confirmations". Only the
     counts are read, never who confirmed.

The release's rule, from the checkout's HomeSwitchRisk.kt (since 0.4.7): the GRADUATED table (the
brand and Android version pairs, models and sets the release gives the switch with the setup wizard
left on) and MOKA_FAMILY (the boards that never get the switch). Over a scan of an earlier release,
a report the test build's switch would take (r0_test_build) and a GRADUATED row covers gets the new
home screen, and a MOKA board keeps its own home screen (BOOT_LOOP). A checkout without the table
(0.4.6) or a profile-meta.json leaves the scan as it is.

Statuses, best first: new (the release gives the new home screen), test ("Next test build": our
test builds do, through the switch with the setup wizard left on for any brand), soon (named in coming-soon.json),
cleanup (the clean-up only, with the reason), no (not possible: Fire OS, TCL China, Android older
than 8). Reports are grouped by brand, model and device, and a TV takes the best status of its
reports. Tested lists no report has reached get a row of their own, judged from the list.

Nothing about people is read or written: no issue authors, no issue numbers, no tester names (the
lists' "tested.by" field is never read). The page links an issue search by model instead.

Writes tvs.json (one TV per line, so a refresh diffs well) and, with -p, the page's list for
browsers without JavaScript, inside its <noscript id="tvlist">.

How to refresh it at each release:

  1. Check out the release tag in a worktree (for 0.4.7: cyt-wt/stable047 at v0.4.7).
  2. Run the release scan over the newest report of every issue (the throwaway Scan046 test, never
     committed) and keep its TSV. 0.4.7 reused the 0.4.6 scan, with the 0.4.7 rule laid over it.
  3. Edit site/tvs/coming-soon.json: drop what shipped, add what the next versions bring.
  4. Run, check the page, then commit site/tvs/ with the release:

       scripts/tv-list.py scan-out-final.tsv ../stable047 -r 0.4.7 -d 2026-10-30 \\
           -t https://github.com/udhaykumarbala/cleanyourtv-app/releases/tag/v0.4.8-beta1 \\
           -o site/tvs/tvs.json -p site/tvs/index.html

     The second argument is the checkout (default: cyt-wt/stable047) or a profile-meta.json; the
     test build's lists and the release's rule are read from a checkout only. When the test build's
     switch rule changes (HomeSwitchRisk.r0Blocker), update r0_test_build() below.

Deterministic: rows sorted, the date from -d, else SOURCE_DATE_EPOCH, else today (UTC).
Stdlib only.
"""
import argparse
import collections
import csv
import datetime
import html
import json
import os
import re
import sys
import urllib.parse

SCHEMA = 1
DEFAULT_APP = "/Users/jarvis/hobby/tv-vibe/cyt-wt/stable047"
DEFAULT_CONFIRMATIONS = "/Users/jarvis/hobby/tv-vibe/research/release-0.4.7/confirmations.json"
PROFILE_META = "core/spec/src/main/resources/tv/cleanyour/core/spec/profile-meta.json"
CANDIDATE_META = "core/spec/candidates/candidate-meta.json"
HOME_SWITCH_RISK = "core/planner/src/main/kotlin/tv/cleanyour/core/planner/HomeSwitchRisk.kt"
REPO = "https://github.com/udhaykumarbala/cleanyourtv-app"
UPSTREAM = "https://github.com/Gathin23/cleanyourtv"

ORDER = ["new", "test", "soon", "cleanup", "no"]
STATUSES = {
    "new": ("New home screen", "The released app cleans up the TV and puts the new home screen on."),
    "test": ("Next test build", "Works in our test builds; coming to everyone in a later release once testers confirm it on your brand."),
    "soon": ("Coming soon", "Being worked on for the version shown; until then the app does the clean-up."),
    "cleanup": ("Clean-up only", "The app switches the clutter off and keeps the TV's own home screen, for the reason shown."),
    "no": ("Not possible", "This TV does not allow another home screen, so the clean-up is all the app can do."),
}

# How the app cleans up, by what it knows about the TV ({brand} is filled in by the page).
CLEANUPS = {
    "list": "Cleans up the TV with its tested list",
    "brand": "Cleans up the TV with the {brand} clean-up, only the switches known to be safe on {brand} TVs",
    "general": "Cleans up the TV with the general clean-up, only the switches known to be safe on any TV",
}
HOMES = {
    "new": "and puts the new home screen on.",
    "test": "and keeps the TV's own home screen for now; the new home screen works on it in our test builds.",
    "soon": "and keeps the TV's own home screen for now.",
    "cleanup": "and keeps the TV's own home screen.",
    "no": "and keeps the TV's own home screen.",
}

# SDK to the Android version people know.
ANDROID = {21: "5", 22: "5.1", 23: "6", 24: "7", 25: "7.1", 26: "8", 27: "8.1", 28: "9", 29: "10", 30: "11",
           31: "12", 32: "12L", 33: "13", 34: "14", 35: "15", 36: "16", 37: "17"}
TV_MIN_SDK = 26          # DefaultPlanner.TV_MIN_SDK

# Why the app keeps the TV's own home screen (the release's HomeBlock), in plain words, and what
# would change it. The not possible ones have no change.
HELPER = "The Home button helper, in the 0.4.8-beta1 test build, leaves the TV's own home screen on and still opens the new one when you press Home."
REASONS = {
    "WIZARD_WIFI": ("The TV's setup wizard saved its Wi-Fi, so the wizard has to stay on, and switching the home screen with it on can crash the TV.", HELPER),
    "WIZARD_WIFI_UNKNOWN": ("The TV could not say who saved its Wi-Fi, so its setup wizard has to stay on, and switching the home screen with it on can crash the TV.", HELPER),
    "WIZARD_STAYS_ON": ("The TV's setup wizard stays on and could take over the Home button once the TV's own home screen is off.", HELPER),
    "UNKNOWN_HOME": ("The TV's home screen is one the app does not know yet, so it is left alone.",
                     "A report from this TV helps the app learn its home screen: in the app, Manage, then Help add this TV."),
    "VENDOR_HOME": ("A system service on this TV also answers the Home button, so the home screen is left alone.", HELPER),
    "BOOT_LOOP": ("On this TV family the setup wizard comes back at every restart, and with the TV's own home screen off the TV can fail to start.", HELPER),
    "CASTING": ("On this Google device the switch needs the setup wizard off, and casting from phones then stops after every restart.", HELPER),
    "REMOTE_BUTTONS": ("The remote's app buttons only work through the TV's own home screen.", HELPER),
    "OTHER_HOME": ("Another home screen app also answers the Home button on this TV, so the switch is left alone.",
                   "Switch the other home screen app off or remove it, then run Setup again."),
    "HOME_CHANGED": ("Home already opens a home screen app that was installed on the TV, and the app keeps that choice.",
                     "Make the TV's own home screen the default again, then run Setup again."),
    "PATCHWALL_FEEDS": ("PatchWall is on while Mi TV+ or its web content is off, and with the TV's own home screen off that can stop the TV starting.",
                        "Switch PatchWall off, or those apps back on, then run Setup again."),
    "FIRE_OS": ("Fire OS keeps Amazon's home screen, so the app does the clean-up only.", None),
    "TCL_CN": ("TCL's China firmware does not allow another home screen, so the app does the clean-up only.", None),
    "OLD_ANDROID": ("The new home screen needs Android 8 or newer, so on this Android the app does the clean-up only.", None),
    "TV_REFUSES": ("This TV refuses other home screens, so the app does the clean-up only.", None),
    "TEST_R0": ("The switch with the setup wizard left on works in our test builds for every brand; a release gives it to a brand once its testers confirm the new home screen stays after a restart.", None),
}
# The same in a few words, for the TV's one line on the page (after "Keeps its own home screen: " or
# "Clean-up only: ").
SHORT = {
    "WIZARD_WIFI": "its setup wizard saved the Wi-Fi.",
    "WIZARD_WIFI_UNKNOWN": "it could not say who saved the Wi-Fi.",
    "WIZARD_STAYS_ON": "its setup wizard could take over Home.",
    "UNKNOWN_HOME": "we don't know it yet.",
    "VENDOR_HOME": "a system service also answers Home.",
    "BOOT_LOOP": "its setup wizard returns at every restart.",
    "CASTING": "casting would stop after a restart.",
    "REMOTE_BUTTONS": "the remote's app buttons need it.",
    "OTHER_HOME": "another home screen app answers Home.",
    "HOME_CHANGED": "another home screen app is the default.",
    "PATCHWALL_FEEDS": "PatchWall is on while its feeds are off.",
    "FIRE_OS": "Fire OS keeps Amazon's home screen.",
    "TCL_CN": "TCL's China firmware allows no other home.",
    "OLD_ANDROID": "the new home screen needs Android 8 or newer.",
    "TV_REFUSES": "this TV refuses other home screens.",
    "TEST_R0": "works in our test builds.",
}
NOT_POSSIBLE = {"FIRE_OS", "TCL_CN", "OLD_ANDROID", "TV_REFUSES"}
WIZARD_BLOCKS = {"WIZARD_STAYS_ON", "WIZARD_WIFI", "WIZARD_WIFI_UNKNOWN"}

# HomeSwitchRisk and its helpers at v0.4.6, for the test build's R0 (anyBrand) on the scan's columns.
TVLAUNCHER = "com.google.android.tvlauncher"
OURS = "com.gathin.tvhome"
SETUP_WIZARDS = {"com.google.android.tungsten.setupwraith"}
VENDOR_WIZARDS = {"com.smartdevice.setupwraith", "com.oneplus.oobe.tv", "com.xiaomi.setupwizard", "com.android.managedprovisioning"}
AOSP_FALLBACK = {("com.android.tv.settings", ".system.FallbackHome"), ("com.android.settings", ".FallbackHome")}
VENDOR_PLACEHOLDERS = {"com.apps.tvsettings", "com.android.boot.fallbackhome", "com.realtek.systemservice"}
CASTING_PROFILES = {"google-chromecast-gtv-4k", "google-chromecast-gtv-hd", "google-tv-streamer-4k-kirkwood"}
MOKA_PROFILES = {"kalley-43g200", "kalley-r10g", "prismplus-q65-ultra-r10g"}
REMOTE_BUTTON_PROFILES = {"sony-bravia-vu3"}

# Brand spellings that are the same brand, and words of a list's name written the way people do.
BRAND_ALIASES = {"hidpt hidpt": "hidpt"}
WORDS = {"tv": "TV", "gtv": "Google TV", "atv": "ATV", "uhd": "UHD", "fhd": "FHD", "hd": "HD", "qled": "QLED", "oled": "OLED",
         "mitv": "MiTV", "mi": "Mi", "eu": "EU", "uk": "UK", "us": "US", "cn": "CN", "android12": "Android 12", "shield": "SHIELD",
         "bravia": "BRAVIA", "aquos": "AQUOS", "ai": "AI", "pont": "PONT", "sa": "SA", "stb": "STB", "pro": "Pro", "max": "Max"}


def comp(flat):
    if not flat or flat == "nothing" or "/" not in flat:
        return None
    pkg, cls = flat.split("/", 1)
    return pkg, cls


def claimants(text):
    out = []
    for item in text.split():
        flat, _, prio = item.rpartition(":")
        c = comp(flat)
        if c:
            out.append((c, int(prio) if re.fullmatch(r"-?\d+", prio) else 0))
    return out


def is_wizard(pkg):
    return pkg in SETUP_WIZARDS or pkg in VENDOR_WIZARDS or pkg == "com.android.provision" or "setupwraith" in pkg.lower()


def is_placeholder(c):
    pkg, cls = c
    return pkg == "android" or "FallbackHome" in cls or is_wizard(pkg)


def r0_placeholder(c):
    if not is_placeholder(c):
        return False
    pkg, cls = c
    if "FallbackHome" not in cls:
        return True
    short = cls[len(pkg):] if cls.startswith(pkg + ".") else cls
    return (pkg, short) in AOSP_FALLBACK or pkg in VENDOR_PLACEHOLDERS


def vendor_placeholder(c):
    return is_placeholder(c) and "FallbackHome" in c[1] and not r0_placeholder(c)


def r0_test_build(row):
    """HomeSwitchRisk.r0Blocker with anyBrand (the test build): True when R0 lets the switch go ahead.

    The scan ran the release rule, so its r0Blocker stops at BRAND or SCOPE before the other tests.
    This repeats those on the report's Home claimants. The report has no package states, so a
    claimant is taken as on, and an app counts as one the person installed only when the report
    hid its name (the scan's person.launcherN); any other app claiming Home blocks, as a system app
    would. On the 87 Xiaomi rows where the scan ran every test, this agrees with the scan.
    """
    if row["r0Blocker"] not in ("BRAND", "SCOPE", "none") or row["heldOut"] == "true":
        return False
    cl = claimants(row["claimants"])
    resolved = comp(row["resolved"])
    comps = [c for c, _ in cl]
    if any(is_wizard(c[0]) for c in comps):
        return False                                    # WIZARD_CLAIMS_HOME
    if not any(c[0] == TVLAUNCHER for c in comps):
        return False                                    # NO_TVLAUNCHER
    if any(vendor_placeholder(c) for c in comps + ([resolved] if resolved else [])):
        return False                                    # VENDOR_PLACEHOLDER
    if resolved and resolved[0] not in (TVLAUNCHER, OURS) and not r0_placeholder(resolved):
        return False                                    # OTHER_HOME
    for c, prio in cl:
        if c[0] in (TVLAUNCHER, OURS) or r0_placeholder(c):
            continue
        if c[0].startswith("person.") and prio <= 0:
            continue
        return False                                    # OTHER_HOME
    return True


def kotlin_strings(text):
    """The string literals of a Kotlin setOf(...)."""
    return re.findall(r'"([^"]*)"', text)


def kotlin_call(text, start):
    """The text inside the parentheses that open at text[start]."""
    depth = 0
    for i in range(start, len(text)):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                return text[start + 1:i]
    raise ValueError("unclosed (")


class ReleaseRule:
    """The release's own switch rule where it differs from the scan's (HomeSwitchRisk.kt of the checkout).

    graduated: the GRADUATED rows, each (makers, sdks, models, devices, excluded), as Graduated.covers
    reads them (brand or manufacturer, case ignored). None when the checkout has no table (0.4.6, whose
    scan already is its rule). moka: MOKA_FAMILY's (device, maker) pairs, plus MOKA_REPORTED.
    """

    def __init__(self, graduated=None, moka=()):
        self.graduated = graduated
        self.moka = set(moka)

    @classmethod
    def from_checkout(cls, path):
        if not os.path.exists(path):
            return cls()
        with open(path, encoding="utf-8") as f:
            src = "\n".join(line for line in f.read().splitlines() if not line.strip().startswith("//"))
        sets = {m.group(1): kotlin_strings(kotlin_call(src, m.end() - 1))
                for m in re.finditer(r"val (\w+): Set<String> = setOf\(", src)}
        pairs = {}
        for m in re.finditer(r"val (MOKA_\w+): Set<Pair<String, String>> = setOf\(", src):
            pairs[m.group(1)] = re.findall(r'"([^"]+)" to "([^"]+)"', kotlin_call(src, m.end() - 1))
        moka = pairs.get("MOKA_FAMILY", []) + pairs.get("MOKA_REPORTED", [])
        m = re.search(r"val GRADUATED: List<Graduated> = listOf\(", src)
        if not m:
            return cls(None, moka)
        table = kotlin_call(src, m.end() - 1)
        rows = []
        for g in re.finditer(r"Graduated\(", table):
            body = kotlin_call(table, g.end() - 1)
            body = body.strip()
            makers = kotlin_strings(kotlin_call(body, 5)) if body.startswith("setOf(") else sets.get(body.split(",", 1)[0].strip())
            sdks = re.search(r"(\d+)\.\.(\d+)", body)
            if not makers or not sdks:
                sys.exit(f"tv-list: cannot read GRADUATED row {body!r}")
            named = {k: [] for k in ("models", "devices", "excludedDevices")}
            for k in named:
                n = re.search(r"\b" + k + r" = setOf\(", body)
                if n:
                    named[k] = kotlin_strings(kotlin_call(body, n.end() - 1))
            rows.append(({x.lower() for x in makers}, range(int(sdks.group(1)), int(sdks.group(2)) + 1),
                         {x.lower() for x in named["models"]}, {x.lower() for x in named["devices"]},
                         {x.lower() for x in named["excludedDevices"]}))
        if not rows:
            sys.exit(f"tv-list: no GRADUATED rows in {path}")
        return cls(rows, moka)

    @staticmethod
    def _maker(g, r):
        return bool({r["brand"].strip().lower(), r["manufacturer"].strip().lower()} & g[0])

    def covers(self, r):
        """Graduated.covers on a report: a row gives this TV the switch with the wizard left on."""
        sdk, model, device = sdk_of(r["android"]), r["model"].lower(), r["device"].lower()
        return any(self._maker(g, r) and sdk in g[1] and (not g[2] or model in g[2]) and (not g[3] or device in g[3])
                   and device not in g[4] for g in self.graduated or [])

    def some_sets(self, r, sdk):
        """A row names this maker and Android version but not this model or set: the release gave the
        switch to some sets of the pair only, so the pair's confirmations are theirs, not this TV's."""
        return any(self._maker(g, r) and sdk in g[1] for g in self.graduated or []) and not self.covers(r)

    def is_moka(self, r):
        dev = r["device"].lower()
        makers = {r["brand"].strip().lower(), r["manufacturer"].strip().lower()}
        return any(d.lower() == dev and m.lower() in makers for d, m in self.moka)


def sdk_of(android):
    m = re.search(r"SDK (\d+)", android)
    return int(m.group(1)) if m else 0


def android_names(sdks):
    return [ANDROID.get(s, f"SDK {s}") for s in sorted(set(sdks)) if s]


def brand_key(b):
    k = re.sub(r"\s+", " ", (b or "").strip().lower())
    return BRAND_ALIASES.get(k, k)


def pick_brand(spellings):
    """The display spelling of a brand: one with a capital first, then the most common, then by name."""
    counts = collections.Counter(spellings)
    return sorted(counts, key=lambda s: (not s[:1].isupper(), -counts[s], s))[0]


def slug(*parts):
    s = "-".join(p for p in parts if p)
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")


def list_title(pid, brand):
    """A tested list's name as people write it: xgimi-mogo-3-pro is MoGo 3 Pro."""
    words = pid.split("-")
    bwords = slug(brand).split("-")
    if words[:len(bwords)] == bwords and len(words) > len(bwords):
        words = words[len(bwords):]
    out = []
    for w in words:
        if w in WORDS:
            out.append(WORDS[w])
        elif w == "mogo":
            out.append("MoGo")
        elif re.search(r"\d", w):
            out.append(w.upper())
        else:
            out.append(w.capitalize())
    return " ".join(out)


def soon_match(item, brand_names, devices, sdks, block, homes, r0, profile_ids):
    m = item.get("match", {})
    low = lambda xs: {x.lower() for x in xs if x}
    if "brands" in m and not (low(m["brands"]) & low(brand_names)):
        return False
    if "devices" in m and not (low(m["devices"]) & low(devices)):
        return False
    if "profiles" in m and not (set(m["profiles"]) & set(profile_ids)):
        return False
    if "sdk" in m and not (set(m["sdk"]) & set(sdks)):
        return False
    if "blocks" in m and block not in m["blocks"]:
        return False
    if "homes" in m and not (set(m["homes"]) & set(homes)):
        return False
    if m.get("r0") and not r0:
        return False
    return True


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def most(counter):
    """The most common key, ties by name, or None."""
    return sorted(counter, key=lambda k: (-counter[k], k))[0] if counter else None


# Pairs whose count was met but where the owner waits for more testers before a release gives them the
# switch (0.4.7: Kogan on Android 11, a third tester, HomeSwitchRisk.GRADUATED's note).
WAITS_LONGER = {("kogan", 30): 3}


class Confirmations:
    """The counts per brand family and SDK from the routine's table (pairs and its rule), nothing else."""

    def __init__(self, doc):
        rule = doc.get("rule", {})
        self.families = rule.get("families", {})
        self.proven = {k: range(v[0], v[1] + 1) for k, v in rule.get("proven", {}).items()}
        self.need_proven, self.need_new = rule.get("need_proven", 2), rule.get("need_new", 3)
        self.held = {(p["brand"], p["sdk"]): p["held"] for p in doc.get("pairs", [])}
        self.failed = {(p["brand"], p["sdk"]): p.get("failed", 0) for p in doc.get("pairs", [])}

    def family(self, brand):
        k = brand_key(brand)
        return self.families.get(k, k)

    def of(self, r, sdk, rule=None):
        """The count for a report's brand and Android version, or None when a count would mislead: the
        pair met it, but a tester's home screen did not hold, so the release left it out."""
        fam = self.family(r["brand"])
        need = self.need_proven if sdk in self.proven.get(fam, ()) else self.need_new
        need = max(need, WAITS_LONGER.get((fam, sdk), need))
        have = self.held.get((fam, sdk), 0)
        sets = bool(rule and rule.some_sets(r, sdk))
        if sets:
            have = 0
        elif self.failed.get((fam, sdk)) and have >= need:
            return None
        return {"have": min(have, need), "need": need, "android": ANDROID.get(sdk, f"SDK {sdk}"), **({"sets": True} if sets else {})}


def build(profiles, cands, reports, soon_items, upstream, confirmations=None, rule=None):
    rule = rule or ReleaseRule()
    spellings = collections.defaultdict(list)
    groups = collections.OrderedDict()
    for r in reports:
        if not r.get("model"):
            continue
        key = (brand_key(r["brand"]), slug(r["model"]), r["device"].lower())
        spellings[key[0]].append(r["brand"].strip())
        groups.setdefault(key, []).append(r)
    for p in list(profiles.values()) + list(cands.values()):
        spellings[brand_key(p["match"]["brand"])].append(p["match"]["brand"].strip())
    brand_display = {k: pick_brand(v) for k, v in spellings.items()}

    def profile_for(brand, model, device, table):
        for pid in sorted(table):
            m = table[pid]["match"]
            if brand_key(m["brand"]) != brand_key(brand) or device.lower() not in {d.lower() for d in m["devices"]}:
                continue
            if m["models"] and model not in m["models"]:
                continue
            return pid
        return None

    def first_soon(names, devices, sdks, block, homes, r0, pids):
        return next((i for i in soon_items if soon_match(i, names, devices, sdks, block, homes, r0, pids)), None)

    def judge(r):
        """(status, reason code, soon item) for one report."""
        sdk = sdk_of(r["android"])
        block = r["b046final"]
        exact = r["res"].split(" ", 1)[1] if r["res"].startswith("Exact ") else None
        platform = profiles[exact]["platform"] if exact in profiles else None
        homes = [c[0] for c, _ in claimants(r["claimants"])] + [x[0] for x in [comp(r["resolved"])] if x]
        r0 = r0_test_build(r)
        if rule.is_moka(r) and block not in ("TV_REFUSES", "OLD_ANDROID"):
            block, r0 = "BOOT_LOOP", False              # the release's MOKA_FAMILY, newer than the scan
        item = first_soon([r["brand"], r["manufacturer"]], [r["device"]], [sdk], block, homes, r0, [exact] if exact else [])
        amazon = "amazon" in (brand_key(r["brand"]), brand_key(r["manufacturer"]))
        if block == "TV_REFUSES":
            code = "FIRE_OS" if platform == "FIRE_OS" or amazon else "TCL_CN" if platform == "TCL_CN" or brand_key(r["brand"]) == "tcl" else "TV_REFUSES"
            return "no", code, None
        if block == "OLD_ANDROID" or sdk < TV_MIN_SDK:
            return "no", "OLD_ANDROID", None
        if item and item.get("override"):
            return "soon", None, item
        if block == "NONE" or (block == "OURS" and exact):
            return "new", None, item
        if block == "OURS":
            # Already the new home screen without a tested list: a test build switched it, and the release
            # keeps it where a GRADUATED row covers the TV.
            return ("new", None, item) if rule.covers(r) else ("test", "TEST_R0", item)
        if r0 and block in WIZARD_BLOCKS:
            # The release's GRADUATED table gives this TV the switch with the wizard left on.
            return ("new", None, item) if rule.covers(r) else ("test", "TEST_R0", item)
        if item:
            return "soon", block if block in REASONS else None, item
        return "cleanup", block if block in REASONS else "UNKNOWN_HOME", None

    rows = []
    covered = set()
    for key, rs in groups.items():
        judged = [judge(r) for r in rs]
        best = min(ORDER.index(j[0]) for j in judged)
        status = ORDER[best]
        top = [j for j in judged if j[0] == status]
        reason = most(collections.Counter(j[1] for j in top if j[1]))
        items = sorted({j[2]["id"] for j in (top if status == "soon" else judged) if j[2]})
        brand = brand_display[key[0]]
        lists, test_lists, kinds = set(), set(), set()
        for r in rs:
            if r["res"].startswith("Exact "):
                lists.add(r["res"].split(" ", 1)[1])
                kinds.add("list")
            elif r["res"].startswith("BrandSureSet"):
                kinds.add("brand")
            pid = profile_for(r["brand"], r["model"], r["device"], profiles)
            if pid:
                lists.add(pid)
            cid = profile_for(r["brand"], r["model"], r["device"], cands)
            if cid:
                test_lists.add(cid)
        covered |= lists
        clean = "list" if "list" in kinds else "brand" if "brand" in kinds else "general"
        row = {
            "id": slug(key[0], key[1], key[2]),
            "brand": brand,
            "name": most(collections.Counter(r["model"] for r in rs)),
            "models": sorted({r["model"] for r in rs}, key=lambda s: (s.lower(), s)),
            "codenames": sorted({r["device"] for r in rs if r["device"]}, key=lambda s: (s.lower(), s)),
            "android": android_names(sdk_of(r["android"]) for r in rs),
            "status": status,
            "clean": clean,
        }
        if reason:
            row["reason"] = reason
        if items:
            row["soon"] = items[0]
        worse = collections.Counter(j[1] for j in judged if ORDER.index(j[0]) > best and j[1] and j[1] != "TEST_R0")
        if worse and status in ("new", "test"):
            row["some"] = most(worse)          # some sets of this model keep their home screen, and why
        if lists:
            row["lists"] = sorted(lists)
        if test_lists:
            row["test_lists"] = sorted(test_lists)
        waits = status == "test" or (status == "soon" and items and
                                     next(i for i in soon_items if i["id"] == items[0]).get("match", {}).get("r0"))
        if confirmations and waits:
            # The count for this TV's brand and Android version (the best of its reports with this status).
            sdks = [sdk_of(r["android"]) for r, j in zip(rs, judged) if j[0] == status and sdk_of(r["android"])]
            if sdks:
                counts = [c for r in rs for sdk in sdks if sdk_of(r["android"]) == sdk for c in [confirmations.of(r, sdk, rule)] if c]
                if counts:
                    row["confirm"] = max(counts, key=lambda c: (c["have"] / c["need"], c["android"]))
        row["reports"] = len(rs)
        rows.append(row)

    # The tested lists no report has reached yet, judged from the list itself.
    for pid in sorted(set(profiles) - covered):
        p = profiles[pid]
        m = p["match"]
        if p["platform"] == "FIRE_OS":
            block = "FIRE_OS"
        elif p["platform"] == "TCL_CN":
            block = "TCL_CN"
        elif m["sdkMax"] and m["sdkMax"] < TV_MIN_SDK:
            block = "OLD_ANDROID"
        elif pid in MOKA_PROFILES:
            block = "BOOT_LOOP"
        elif pid in CASTING_PROFILES:
            block = "CASTING"
        elif pid in REMOTE_BUTTON_PROFILES:
            block = "REMOTE_BUTTONS"
        elif not p["stockLaunchers"]:
            block = "UNKNOWN_HOME"
        else:
            block = "NONE"
        sdks = list(range(m["sdkMin"], m["sdkMax"] + 1)) if m["sdkMin"] and m["sdkMax"] else []
        brand = brand_display[brand_key(m["brand"])]
        item = first_soon([m["brand"]], m["devices"], sdks, block, p["stockLaunchers"], False, [pid])
        if block in NOT_POSSIBLE:
            status = "no"
        elif item:
            status = "soon"
        elif block == "NONE":
            status = "new"
        else:
            status = "cleanup"
        row = {
            "id": slug("list", pid),
            "brand": brand,
            "name": list_title(pid, m["brand"]),
            "models": sorted(m["models"], key=lambda s: (s.lower(), s)),
            "codenames": sorted(m["devices"], key=lambda s: (s.lower(), s)),
            "android": android_names(sdks),
            "status": status,
            "clean": "list",
        }
        if status in ("no", "cleanup"):
            row["reason"] = block
        if item:
            row["soon"] = item["id"]
        row["lists"] = [pid]
        row["reports"] = 0
        rows.append(row)

    rows.sort(key=lambda t: (t["brand"].lower(), t["name"].lower(), t["id"]))
    dup = [k for k, n in collections.Counter(t["id"] for t in rows).items() if n > 1]
    if dup:
        sys.exit(f"tv-list: duplicate ids {dup}")
    return rows


def noscript(rows, statuses):
    """The page's list without JavaScript: one line per TV, grouped by status."""
    out = []
    for s in ORDER:
        mine = [t for t in rows if t["status"] == s]
        out.append(f'<h2 class="ns-h">{html.escape(statuses[s][0])} <span>{len(mine)}</span></h2>')
        out.append(f'<p class="ns-m">{html.escape(statuses[s][1])}</p>')
        out.append('<ul class="ns">')
        for t in mine:
            extra = ", ".join(x for x in (", ".join(t["codenames"]), ("Android " + ", ".join(t["android"])) if t["android"] else "",
                                          confirm_text(t)) if x)
            q = urllib.parse.quote(t["models"][0] if t["models"] else t["name"])
            out.append(f'<li><b>{html.escape(t["brand"])} {html.escape(t["name"])}</b> <small>{html.escape(extra)}</small>'
                       f' <a href="{REPO}/issues?q={q}">issues</a></li>')
        out.append("</ul>")
    return "\n".join(out)


def confirm_text(t):
    c = t.get("confirm")
    return f'{c["have"]} of {c["need"]} confirmations' if c else ""


def dumps(doc):
    """JSON with one TV per line."""
    head = {k: v for k, v in doc.items() if k != "tvs"}
    text = json.dumps(head, indent=1, ensure_ascii=False)
    tvs = ",\n".join("  " + json.dumps(t, ensure_ascii=False, separators=(",", ":")) for t in doc["tvs"])
    return text[:-2] + ',\n "tvs": [\n' + tvs + "\n ]\n}\n"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("scan", help="the release scan TSV")
    ap.add_argument("app", nargs="?", default=DEFAULT_APP,
                    help="app checkout at the release tag, or its profile-meta.json (default: %(default)s)")
    ap.add_argument("-c", dest="coming_soon", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "site", "tvs", "coming-soon.json"),
                    help="the coming soon file (default: site/tvs/coming-soon.json)")
    ap.add_argument("-r", dest="release", default="0.4.7", help="the release the scan and lists are from (default: %(default)s)")
    ap.add_argument("-d", dest="date", help="generated_at as YYYY-MM-DD (default: SOURCE_DATE_EPOCH, else today)")
    ap.add_argument("-o", dest="out", default="-", help="output file (default: stdout)")
    ap.add_argument("-t", "--test-build-url", dest="test_build_url",
                    help="a link to the published test build for the Next test build TVs (default: none, so the page links no pre-release)")
    ap.add_argument("-p", dest="page", help="the page whose noscript list to refresh (site/tvs/index.html)")
    ap.add_argument("-k", dest="confirmations", default=DEFAULT_CONFIRMATIONS if os.path.exists(DEFAULT_CONFIRMATIONS) else None,
                    help="the issue routine's confirmations.json, for N of M confirmations (default: %(default)s when it exists; '' for none)")
    a = ap.parse_args(argv)

    if a.app.endswith(".json"):
        meta_path, cand_path = a.app, ""
    else:
        meta_path, cand_path = os.path.join(a.app, PROFILE_META), os.path.join(a.app, CANDIDATE_META)
    meta = load(meta_path)
    rule = ReleaseRule.from_checkout(os.path.join(a.app, HOME_SWITCH_RISK)) if cand_path else ReleaseRule()
    cands = load(cand_path)["profiles"] if cand_path and os.path.exists(cand_path) else {}
    soon = load(a.coming_soon)
    upstream = meta.get("upstreamCommit", "main")
    with open(a.scan, encoding="utf-8", newline="") as f:
        reports = list(csv.DictReader(f, delimiter="\t"))

    confirmations = Confirmations(load(a.confirmations)) if a.confirmations else None
    rows = build(meta["profiles"], cands, reports, soon["items"], upstream, confirmations, rule)

    if a.date:
        date = a.date
    elif os.environ.get("SOURCE_DATE_EPOCH"):
        date = datetime.datetime.fromtimestamp(int(os.environ["SOURCE_DATE_EPOCH"]), datetime.timezone.utc).strftime("%Y-%m-%d")
    else:
        date = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d")
    counts = collections.Counter(t["status"] for t in rows)
    doc = {
        "schema": SCHEMA,
        "generated_at": date,
        "release": a.release,
        "sources": {"lists": len(meta["profiles"]), "test_lists": len(cands), "reports": len(reports), "upstream": upstream,
                    **({"confirmed": sum(confirmations.held.values())} if confirmations else {})},
        "links": {"issues": f"{REPO}/issues?q=", "list": f"{UPSTREAM}/tree/{upstream}/examples/", "app": f"{REPO}/releases/latest",
                  **({"test_build": a.test_build_url} if a.test_build_url else {})},
        "statuses": [{"id": s, "label": STATUSES[s][0], "means": STATUSES[s][1], "tvs": counts.get(s, 0)} for s in ORDER],
        "cleanups": CLEANUPS,
        "homes": HOMES,
        "reasons": {k: {"why": v[0], "short": SHORT[k], **({"change": v[1]} if v[1] else {})} for k, v in sorted(REASONS.items())},
        "coming_soon": [{"id": i["id"], "title": i["title"], "version": i["version"], "short": i["short"], "what": i["what"]} for i in soon["items"]],
        "tvs": rows,
    }
    text = dumps(doc)
    if a.out == "-":
        sys.stdout.write(text)
    else:
        with open(a.out, "w", encoding="utf-8") as f:
            f.write(text)
    if a.page:
        with open(a.page, encoding="utf-8") as f:
            page = f.read()
        start, end = '<noscript id="tvlist">', "</noscript>"
        if start not in page:
            sys.exit(f"tv-list: {a.page} has no {start}")
        before, rest = page.split(start, 1)
        after = rest.split(end, 1)[1]
        with open(a.page, "w", encoding="utf-8") as f:
            f.write(before + start + "\n" + noscript(rows, STATUSES) + "\n" + end + after)
    print(f"tv-list: {len(rows)} TVs, " + ", ".join(f"{s} {counts.get(s, 0)}" for s in ORDER), file=sys.stderr)


if __name__ == "__main__":
    main()

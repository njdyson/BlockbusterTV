#!/usr/bin/env python3
"""Builds app/src/main/assets/link_tests.json for the Link Lab harness.

For each UK service it asks JustWatch (unofficial GraphQL API, region GB) for a
film currently on that service, then records several candidate links for it:
JustWatch's own Fire TV and Android TV deep links, the plain web URL, and any
hand-written variants from the spec. Run it again to refresh the films.

Usage: python3 tools/fetch_link_tests.py
"""
import json
import os
import urllib.request

API = "https://apis.justwatch.com/graphql"
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "link_tests.json")

# JustWatch package short name -> (service key, display name, candidate app packages on Fire TV / Android TV)
SERVICES = [
    ("nfx", "netflix", "Netflix", ["com.netflix.ninja", "com.netflix.mediaclient"]),
    ("amp", "prime", "Prime Video", ["com.amazon.firebat", "com.amazon.avod", "com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"]),
    ("dnp", "disney", "Disney+", ["com.disney.disneyplus"]),
    ("atp", "appletv", "Apple TV", ["com.apple.atve.amazon.appletv", "com.apple.atve.androidtv.appletv"]),
    ("bbc", "iplayer", "BBC iPlayer", ["uk.co.bbc.iplayer", "bbc.iplayer.android", "uk.co.bbc.iplayer.tv"]),
    ("itv", "itvx", "ITVX", ["air.ITVMobilePlayer", "com.itv.tenft.itvhub"]),
    ("al4", "channel4", "Channel 4", ["com.channel4.ondemand", "com.channel4.ondemand.firetv"]),
    ("ntv", "now", "NOW", ["com.nowtv.nowtvapp", "com.bskyb.nowtv.beta", "com.nowtv.firetv"]),
    ("plx", "plex", "Plex", ["com.plexapp.android"]),
]

YOUTUBE_PACKAGES = ["com.amazon.firetv.youtube", "com.google.android.youtube.tv", "com.google.android.youtube"]

QUERY = """
query($pkg: String!) {
  popularTitles(country: GB, first: 5, filter: {objectTypes: [MOVIE], packages: [$pkg]}) {
    edges { node { ... on Movie {
      content(country: GB, language: "en") {
        title originalReleaseYear externalIds { imdbId }
      }
      offers(country: GB, platform: WEB, filter: {packages: [$pkg]}) {
        standardWebURL
        fireTv: deeplinkURL(platform: FIRE_TV)
        androidTv: deeplinkURL(platform: ANDROID_TV)
      }
    } } }
  }
}
"""


def gql(query, variables):
    req = urllib.request.Request(
        API,
        data=json.dumps({"query": query, "variables": variables}).encode(),
        headers={"Content-Type": "application/json", "User-Agent": "BlockbusterTV-dev/0.1"},
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        body = json.load(resp)
    if body.get("errors"):
        raise RuntimeError(body["errors"])
    return body["data"]


def pick_film(short_name):
    """First popular film on the service that has a web URL, preferring one with a Fire TV link."""
    edges = gql(QUERY, {"pkg": short_name})["popularTitles"]["edges"]
    films = [e["node"] for e in edges if e["node"].get("offers")]
    films.sort(key=lambda n: not any(o.get("fireTv") for o in n["offers"]))
    return films[0] if films else None


def manual_variants(service, web_url):
    """Hand-written link formats from the spec, derived from the web URL."""
    out = []
    if service == "netflix" and "/title/" in web_url:
        netflix_id = web_url.rstrip("/").split("/")[-1]
        out += [
            ("Spec: https title URL", f"https://www.netflix.com/title/{netflix_id}", "com.netflix.ninja"),
            ("https watch URL", f"https://www.netflix.com/watch/{netflix_id}", "com.netflix.ninja"),
            ("netflix:// scheme", f"netflix://title/{netflix_id}", None),
        ]
    if service == "prime" and "gti=" in web_url:
        gti = web_url.split("gti=")[1].split("&")[0]
        out += [
            ("Spec: app.primevideo.com detail", f"https://app.primevideo.com/detail?gti={gti}", None),
        ]
    return out


def main():
    tests = []
    for short, key, name, packages in SERVICES:
        film = pick_film(short)
        if not film:
            print(f"!! nothing found for {name}")
            continue
        content = film["content"]
        offer = next((o for o in film["offers"] if o.get("fireTv")), film["offers"][0])
        label = f'{content["title"]} ({content["originalReleaseYear"]})'
        links = []
        if offer.get("fireTv"):
            links.append({"label": "JustWatch Fire TV link", "uri": offer["fireTv"]})
        if offer.get("androidTv") and offer["androidTv"] != offer.get("fireTv"):
            links.append({"label": "JustWatch Android TV link", "uri": offer["androidTv"]})
        links.append({"label": "Web URL (no package)", "uri": offer["standardWebURL"]})
        for lbl, uri, pkg in manual_variants(key, offer["standardWebURL"]):
            links.append({"label": lbl, "uri": uri, "package": pkg})
        tests.append({
            "service": key,
            "serviceName": name,
            "film": label,
            "imdbId": content["externalIds"]["imdbId"],
            "packages": packages,
            "links": links,
        })
        print(f"{name}: {label}, {len(links)} links")

    # YouTube: trailer, the spec's purchase search hand-off, and the film's
    # YouTube Movies store page (store video IDs come from Wikidata P1651).
    tests.append({
        "service": "youtube",
        "serviceName": "YouTube",
        "film": "Inception (2010)",
        "imdbId": "tt1375666",
        "packages": YOUTUBE_PACKAGES,
        "links": [
            {"label": "Trailer via vnd.youtube://", "uri": "vnd.youtube://YoHD9XEInc0"},
            {"label": "Trailer via https watch URL", "uri": "https://www.youtube.com/watch?v=YoHD9XEInc0"},
            {"label": "Store page via vnd.youtube://", "uri": "vnd.youtube://bewVnEugEqE"},
            {"label": "Store page via https watch URL", "uri": "https://www.youtube.com/watch?v=bewVnEugEqE"},
            {"label": "Search for film (buy flow)", "uri": "https://www.youtube.com/results?search_query=Inception+2010"},
        ],
    })

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w") as f:
        json.dump({"region": "GB", "tests": tests}, f, indent=2)
    print(f"wrote {os.path.relpath(OUT)}")


if __name__ == "__main__":
    main()

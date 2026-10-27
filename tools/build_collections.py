#!/usr/bin/env python3
"""Builds app/src/main/assets/collections.json, the prototype's hard-coded shelves.

Films are listed by IMDb ID (the spec's primary key) with a title hint. The script
looks each one up on JustWatch (GB) and stores its JustWatch node ID, which the
app uses to fetch live metadata and availability in one batched request.

Usage: python3 tools/build_collections.py
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from fetch_link_tests import gql  # noqa: E402

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "collections.json")

SHELVES = {
    "Saturday Night Classics": [
        ("tt0088763", "Back to the Future"),
        ("tt0107290", "Jurassic Park"),
        ("tt0087332", "Ghostbusters"),
        ("tt0082971", "Raiders of the Lost Ark"),
        ("tt0093779", "The Princess Bride"),
        ("tt0425112", "Hot Fuzz"),
        ("tt0133093", "The Matrix"),
        ("tt1375666", "Inception"),
        ("tt0111161", "The Shawshank Redemption"),
    ],
    "Family Night": [
        ("tt4468740", "Paddington 2"),
        ("tt0114709", "Toy Story"),
        ("tt0327597", "Coraline"),
        ("tt0245429", "Spirited Away"),
        ("tt1049413", "Up"),
        ("tt0126029", "Shrek"),
        ("tt0083866", "E.T. the Extra-Terrestrial"),
        ("tt0099785", "Home Alone"),
    ],
}

SEARCH = """
query($q: String!) {
  popularTitles(country: GB, first: 10, filter: {objectTypes: [MOVIE], searchQuery: $q}) {
    edges { node { id ... on Movie { content(country: GB, language: "en") { title externalIds { imdbId } } } } }
  }
}
"""


def find_node(imdb_id, title):
    for edge in gql(SEARCH, {"q": title})["popularTitles"]["edges"]:
        node = edge["node"]
        if node["content"]["externalIds"]["imdbId"] == imdb_id:
            return node["id"]
    raise LookupError(f"{title} ({imdb_id}) not found on JustWatch")


def main():
    shelves = []
    for name, films in SHELVES.items():
        entries = []
        for imdb_id, title in films:
            entries.append({"imdbId": imdb_id, "jwId": find_node(imdb_id, title), "title": title})
            print(f"  {title}: {entries[-1]['jwId']}")
        shelves.append({"name": name, "films": entries})
    with open(OUT, "w") as f:
        json.dump({"collections": shelves}, f, indent=2)
    print(f"wrote {os.path.relpath(OUT)}")


if __name__ == "__main__":
    main()

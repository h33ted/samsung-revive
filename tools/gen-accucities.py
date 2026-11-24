#!/usr/bin/env python3
"""Builds assets/accucities.tsv (AccuWeather cityId -> name, state, country) from the
worldcity.db bundled in Samsung's AccuweatherPhone2013.apk. The DB has no coordinates;
Samsung Revive geocodes each city on first use."""
import sqlite3, sys
db = sqlite3.connect(sys.argv[1])
with open(sys.argv[2] if len(sys.argv) > 2 else "assets/accucities.tsv", "w", encoding="utf-8") as out:
    for loc, city, state, country in db.execute(
            "select LOCCODE, CITYNAME, STATE, COUNTRYNAME from INFOALARM_WEATHER_CITY order by _id"):
        if not loc or not loc.startswith("cityId:"): continue
        clean = lambda s: (s or "").replace("\t", " ").replace("\n", " ").strip()
        out.write("\t".join([loc[7:], clean(city), clean(state), clean(country)]) + "\n")

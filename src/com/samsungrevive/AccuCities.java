package com.samsungrevive;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * AccuWeather cityId -> {name, state, country}, from the worldcity.db that Samsung's 2013
 * AccuWeather app ships for its offline city search (tools/gen-accucities.py). The DB has no
 * coordinates, so WeatherHandler geocodes each city once and caches the result.
 */
final class AccuCities {
    private static Map<String, String[]> table;

    private AccuCities() {}

    static synchronized String[] byId(Context ctx, String id) {
        if (table == null) {
            table = new HashMap<String, String[]>();
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(ctx.getAssets().open("accucities.tsv"), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    String[] f = line.split("\t", -1);
                    if (f.length >= 4) table.put(f[0], new String[]{f[1], f[2], f[3]});
                }
                r.close();
            } catch (IOException e) {
                throw new RuntimeException("accucities.tsv missing", e);
            }
        }
        return table.get(id);
    }
}

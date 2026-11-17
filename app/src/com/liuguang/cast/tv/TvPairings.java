package com.liuguang.cast.tv;

import android.content.Context;
import android.content.SharedPreferences;

import com.liuguang.cast.shared.Pairing;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

final class TvPairings {
    private static final int LIMIT = 32;
    private final SharedPreferences prefs;

    TvPairings(Context context) {
        prefs = context.getSharedPreferences("paired_phones", Context.MODE_PRIVATE);
    }

    boolean accepts(String token) throws GeneralSecurityException {
        return Pairing.validToken(token) && hashes().contains(Pairing.tokenHash(token));
    }

    // A null token means the owner must first clear the TV's saved pairings.
    String issue() throws GeneralSecurityException, IOException {
        Set<String> updated = new HashSet<>(hashes());
        if (updated.size() >= LIMIT) return null;
        String token = Pairing.newToken();
        updated.add(Pairing.tokenHash(token));
        if (!prefs.edit().putStringSet("hashes", updated).commit()) {
            throw new IOException("无法保存电视配对");
        }
        return token;
    }

    void clear() { prefs.edit().remove("hashes").apply(); }

    private Set<String> hashes() { return prefs.getStringSet("hashes", Collections.emptySet()); }
}

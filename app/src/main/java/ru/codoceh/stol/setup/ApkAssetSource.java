package ru.codoceh.stol.setup;

import android.content.res.AssetManager;

import java.io.IOException;
import java.io.InputStream;

/** Образ лежит внутри APK (R11.1): сети на этом шаге нет вообще. */
final class ApkAssetSource implements AssetSource {

    private final AssetManager assets;

    ApkAssetSource(AssetManager assets) {
        this.assets = assets;
    }

    @Override
    public InputStream open(String name) throws IOException {
        // ACCESS_STREAMING: образ на сотни мегабайт целиком в память не поднимаем.
        return assets.open(name, AssetManager.ACCESS_STREAMING);
    }
}

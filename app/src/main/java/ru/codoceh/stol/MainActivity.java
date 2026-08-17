package ru.codoceh.stol;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import ru.codoceh.stol.runtime.NativeAssets;
import ru.codoceh.stol.runtime.ProotSession;

/** Заглушка главного экрана: мастер первого запуска и клиент VNC приезжают отдельно. */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ProotSession session = new ProotSession(
                NativeAssets.nativeDir(this), NativeAssets.rootfsDir(this));
        if (session.isRootfsReady()) {
            ((TextView) findViewById(R.id.state)).setText(R.string.state_ready);
        }
    }
}

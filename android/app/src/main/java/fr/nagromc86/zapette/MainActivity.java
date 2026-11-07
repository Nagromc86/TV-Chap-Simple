package fr.nagromc86.zapette;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(SamsungRemotePlugin.class);
        super.onCreate(savedInstanceState);
    }
}

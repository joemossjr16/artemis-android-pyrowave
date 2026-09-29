package com.limelight;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.UnknownHostException;

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.limelight.binding.PlatformBinding;
import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.computers.PairedComputerBackup;
import com.limelight.grid.PcGridAdapter;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.http.PairingManager.PairState;
import com.limelight.nvstream.wol.WakeOnLanSender;
import com.limelight.preferences.AddComputerManually;
import com.limelight.preferences.GlPreferences;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.StreamSettings;
import com.limelight.preferences.StreamSettingsHost;
import com.limelight.profiles.ProfilesManager;
import com.limelight.ui.AdapterFragment;
import com.limelight.ui.AdapterFragmentCallbacks;
import com.limelight.utils.Dialog;
import com.limelight.utils.HelpLauncher;
import com.limelight.utils.M3Motion;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.SpinnerDialog;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.UiHelper;

import android.app.ActivityManager;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View.OnClickListener;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.AdapterContextMenuInfo;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import org.xmlpull.v1.XmlPullParserException;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class PcView extends AppCompatActivity implements AdapterFragmentCallbacks, StreamSettingsHost {
    private RelativeLayout noPcFoundLayout;
    private PcGridAdapter pcGridAdapter;
    private FrameLayout foldSettingsContainer;
    private View foldSettingsScrim;
    private ShortcutHelper shortcutHelper;
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private boolean freezeUpdates, runningPolling, inForeground, completeOnCreateCalled;
    private ComputerDetails.AddressTuple pendingPairingAddress;
    private String pendingPairingPin, pendingPairingPassphrase;
    private String pendingBackupPassphrase;
    private String pendingBackupComputerUuid;
    private String pendingRestoreComputerUuid;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    ((ComputerManagerService.ComputerManagerBinder)binder);

            // Wait in a separate thread to avoid stalling the UI
            new Thread() {
                @Override
                public void run() {
                    // Wait for the binder to be ready
                    localBinder.waitForReady();

                    // Now make the binder visible
                    managerBinder = localBinder;

                    // Start updates
                    startComputerUpdates();

                    // Force a keypair to be generated early to avoid discovery delays
                    new AndroidCryptoProvider(PcView.this).getClientCertificate();
                }
            }.start();
        }

        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // Only reinitialize views if completeOnCreate() was called
        // before this callback. If it was not, completeOnCreate() will
        // handle initializing views with the config change accounted for.
        // This is not prone to races because both callbacks are invoked
        // in the main thread.
        if (completeOnCreateCalled) {
            // Reinitialize views just in case orientation changed
            initializeViews();
        }

        refreshProfileButton();
    }

    private final static int PAIR_ID = 2;
    private final static int UNPAIR_ID = 3;
    private final static int WOL_ID = 4;
    private final static int DELETE_ID = 5;
    private final static int RESUME_ID = 6;
    private final static int QUIT_ID = 7;
    private final static int VIEW_DETAILS_ID = 8;
    private final static int FULL_APP_LIST_ID = 9;
    private final static int TEST_NETWORK_ID = 10;
    private final static int GAMESTREAM_EOL_ID = 11;
    private final static int OPEN_MANAGEMENT_PAGE_ID = 20;
    private final static int PAIR_ID_OTP = 21;
    private final static int BACKUP_PAIRING_ID = 22;
    private final static int RESTORE_PAIRING_ID = 23;
    private final static int SPEED_TEST_ID = 24;
    private static final int CREATE_PC_BACKUP_REQUEST = 1201;
    private static final int RESTORE_PC_BACKUP_REQUEST = 1202;

    private void initializeViews() {
        if (foldSettingsContainer != null) {
            closeFoldSettingsPane();
        }
        setContentView(R.layout.activity_pc_view);
        M3Motion.enter(findViewById(android.R.id.content), 0);

        UiHelper.notifyNewRootView(this);

        // Allow floating expanded PiP overlays while browsing PCs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false);
        }

        // Set default preferences if we've never been run
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        // Set the correct layout for the PC grid
        pcGridAdapter.updateLayoutWithPreferences(this, PreferenceConfiguration.readPreferences(this));

        // Setup the list view
        View settingsButton = findViewById(R.id.settingsButton);
        ImageButton addComputerButton = findViewById(R.id.manuallyAddPc);
        View helpButton = findViewById(R.id.helpButton);
        ExtendedFloatingActionButton profilesButton = findViewById(R.id.profilesButton);

        settingsButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (getResources().getConfiguration().screenWidthDp >= 600) {
                    showFoldSettingsPane();
                } else {
                    startActivity(new Intent(PcView.this, StreamSettings.class));
                }
            }
        });
        addComputerButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(PcView.this, AddComputerManually.class);
                startActivity(i);
            }
        });
        helpButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                HelpLauncher.launchSetupGuide(PcView.this);
            }
        });
        profilesButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(PcView.this, ProfilesActivity.class));
            }
        });

        // Amazon review didn't like the help button because the wiki was not entirely
        // navigable via the Fire TV remote (though the relevant parts were). Let's hide
        // it on Fire TV.
        if (getPackageManager().hasSystemFeature("amazon.hardware.fire_tv")) {
            helpButton.setVisibility(View.GONE);
        }

        getFragmentManager().beginTransaction()
            .replace(R.id.pcFragmentContainer, new AdapterFragment())
            .commitAllowingStateLoss();

        noPcFoundLayout = findViewById(R.id.no_pc_found_layout);
        if (pcGridAdapter.getCount() == 0) {
            noPcFoundLayout.setVisibility(View.VISIBLE);
        }
        else {
            noPcFoundLayout.setVisibility(View.INVISIBLE);
        }
        pcGridAdapter.notifyDataSetChanged();
    }

    private void showFoldSettingsPane() {
        if (foldSettingsContainer != null) {
            return;
        }

        float density = getResources().getDisplayMetrics().density;
        int panelWidth = Math.min(Math.round(460 * density), getResources().getDisplayMetrics().widthPixels);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        boolean amoled = PreferenceConfiguration.readPreferences(this).amoledTheme;
        android.graphics.drawable.GradientDrawable panelBackground = new android.graphics.drawable.GradientDrawable();
        panelBackground.setColor(androidx.core.content.ContextCompat.getColor(this,
                amoled ? android.R.color.black : R.color.m3SurfaceContainer));
        float corner = 28 * density;
        panelBackground.setCornerRadii(new float[]{corner, corner, 0, 0, 0, 0, corner, corner});
        panel.setBackground(panelBackground);
        panel.setElevation(20 * density);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Math.round(20 * density), 0, Math.round(20 * density), 0);
        ImageButton close = new ImageButton(this);
        close.setImageResource(R.drawable.ic_back);
        close.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        close.setColorFilter(androidx.core.content.ContextCompat.getColor(this, R.color.m3OnSurface));
        close.setContentDescription(getString(R.string.m3_back));
        header.addView(close, new LinearLayout.LayoutParams(Math.round(48 * density), Math.round(56 * density)));
        TextView title = new TextView(this);
        title.setText(R.string.m3_streaming_settings);
        title.setTextSize(20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.m3OnSurface));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleParams.leftMargin = Math.round(12 * density);
        header.addView(title, titleParams);
        panel.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(64 * density)));

        foldSettingsContainer = new FrameLayout(this);
        foldSettingsContainer.setId(View.generateViewId());
        panel.addView(foldSettingsContainer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        FrameLayout root = findViewById(android.R.id.content);
        foldSettingsScrim = new View(this);
        foldSettingsScrim.setBackgroundColor(android.graphics.Color.BLACK);
        foldSettingsScrim.setAlpha(0.42f);
        root.addView(foldSettingsScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        foldSettingsScrim.setOnClickListener(v -> closeFoldSettingsPane());
        panel.setAlpha(0f);
        panel.setTranslationX(24 * density);
        root.addView(panel, new FrameLayout.LayoutParams(panelWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));
        panel.animate().alpha(1f).translationX(0).setDuration(220).start();
        close.setOnClickListener(v -> closeFoldSettingsPane());
        getSupportFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .replace(foldSettingsContainer.getId(), new StreamSettings.SettingsFragment(
                        PreferenceConfiguration.readPreferences(this)))
                .commit();
    }

    private void closeFoldSettingsPane() {
        if (foldSettingsContainer != null) {
            androidx.fragment.app.Fragment fragment = getSupportFragmentManager().findFragmentById(foldSettingsContainer.getId());
            if (fragment != null) {
                getSupportFragmentManager().beginTransaction().remove(fragment).commit();
            }
            View panel = (View) foldSettingsContainer.getParent();
            ViewGroup root = (ViewGroup) panel.getParent();
            root.removeView(panel);
            if (foldSettingsScrim != null) {
                root.removeView(foldSettingsScrim);
                foldSettingsScrim = null;
            }
            foldSettingsContainer = null;
        }
    }

    @Override
    public void reloadSettings() {
        if (foldSettingsContainer != null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(foldSettingsContainer.getId(), new StreamSettings.SettingsFragment(
                            PreferenceConfiguration.readPreferences(this)))
                    .commitAllowingStateLoss();
        }
    }

    @Override
    public void onBackPressed() {
        if (foldSettingsContainer != null) {
            closeFoldSettingsPane();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        PreferenceConfiguration.applyAmoledThemeIfEnabled(this);
        super.onCreate(savedInstanceState);

        // Assume we're in the foreground when created to avoid a race
        // between binding to CMS and onResume()
        inForeground = true;

        // Create a GLSurfaceView to fetch GLRenderer unless we have
        // a cached result already.
        final GlPreferences glPrefs = GlPreferences.readPreferences(this);
        if (!glPrefs.savedFingerprint.equals(Build.FINGERPRINT) || glPrefs.glRenderer.isEmpty()) {
            GLSurfaceView surfaceView = new GLSurfaceView(this);
            surfaceView.setRenderer(new GLSurfaceView.Renderer() {
                @Override
                public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {
                    // Save the GLRenderer string so we don't need to do this next time
                    glPrefs.glRenderer = gl10.glGetString(GL10.GL_RENDERER);
                    glPrefs.savedFingerprint = Build.FINGERPRINT;
                    glPrefs.writePreferences();

                    LimeLog.info("Fetched GL Renderer: " + glPrefs.glRenderer);

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            completeOnCreate();
                        }
                    });
                }

                @Override
                public void onSurfaceChanged(GL10 gl10, int i, int i1) {
                }

                @Override
                public void onDrawFrame(GL10 gl10) {
                }
            });
            setContentView(surfaceView);
        }
        else {
            LimeLog.info("Cached GL Renderer: " + glPrefs.glRenderer);
            completeOnCreate();
        }

        Intent intent = getIntent();

        String hostname = intent.getStringExtra("hostname");
        int port = intent.getIntExtra("port", NvHTTP.DEFAULT_HTTP_PORT);
        pendingPairingPin = intent.getStringExtra("pin");
        pendingPairingPassphrase = intent.getStringExtra("passphrase");

        if (hostname != null && pendingPairingPin != null && pendingPairingPassphrase != null) {
            pendingPairingAddress = new ComputerDetails.AddressTuple(hostname, port);
        } else {
            pendingPairingPin = null;
            pendingPairingPassphrase = null;
        }
    }

    private void completeOnCreate() {
        completeOnCreateCalled = true;

        shortcutHelper = new ShortcutHelper(this);

        UiHelper.setLocale(this);

        // Bind to the computer manager service
        bindService(new Intent(PcView.this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);

        pcGridAdapter = new PcGridAdapter(this, PreferenceConfiguration.readPreferences(this));

        initializeViews();
    }

    private void startComputerUpdates() {
        // Only allow polling to start if we're bound to CMS, polling is not already running,
        // and our activity is in the foreground.
        if (managerBinder != null && !runningPolling && inForeground) {
            freezeUpdates = false;
            managerBinder.startPolling(new ComputerManagerListener() {
                @Override
                public void notifyComputerUpdated(final ComputerDetails details) {
                    if (!freezeUpdates) {
                        PcView.this.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                updateComputer(details);
                            }
                        });

                        // Add a launcher shortcut for this PC (off the main thread to prevent ANRs)
                        if (details.pairState == PairState.PAIRED) {
                            shortcutHelper.createAppViewShortcutForOnlineHost(details);
//                        } else
                        }
                            if (pendingPairingAddress != null) {
                            if (
                                details.state == ComputerDetails.State.ONLINE &&
                                details.activeAddress.equals(pendingPairingAddress)
                            ) {
                                PcView.this.runOnUiThread(() -> {
                                    doPair(details, pendingPairingPin, pendingPairingPassphrase);
                                    pendingPairingAddress = null;
                                    pendingPairingPin = null;
                                    pendingPairingPassphrase = null;
                                });
                            }
                        }
                    }
                }
            });
            runningPolling = true;
        }
    }

    private void stopComputerUpdates(boolean wait) {
        if (managerBinder != null) {
            if (!runningPolling) {
                return;
            }

            freezeUpdates = true;

            managerBinder.stopPolling();

            if (wait) {
                managerBinder.waitForPollingStopped();
            }

            runningPolling = false;
        }
    }

    private void refreshProfileButton() {
        ExtendedFloatingActionButton profilesButton = findViewById(R.id.profilesButton);
        // User report Samsung and Xiaomi devices have this problem
        // Why just these two brands have the most problems?
        if (profilesButton == null) {
            return;
        }
        String activeProfileName = ProfilesManager.getInstance().getActiveName();
        if (activeProfileName.isEmpty()) {
            profilesButton.shrink();
        } else {
            profilesButton.setText(activeProfileName);
            profilesButton.extend();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Display a decoder crash notification if we've returned after a crash
        UiHelper.showDecoderCrashDialog(this);

        refreshProfileButton();

        inForeground = true;
        startComputerUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();

        inForeground = false;
        stopComputerUpdates(false);
    }

    @Override
    protected void onStop() {
        super.onStop();

        SpinnerDialog.closeDialogs(this);
        Dialog.closeDialogs();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        stopComputerUpdates(false);

        // Call superclass
        super.onCreateContextMenu(menu, v, menuInfo);

        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(info.position);

        // Add a header with PC status details
        menu.clearHeader();
        String headerTitle = computer.details.name + " - ";
        switch (computer.details.state)
        {
            case ONLINE:
                headerTitle += getResources().getString(R.string.pcview_menu_header_online);
                break;
            case OFFLINE:
                menu.setHeaderIcon(R.drawable.ic_pc_offline);
                headerTitle += getResources().getString(R.string.pcview_menu_header_offline);
                break;
            case UNKNOWN:
                headerTitle += getResources().getString(R.string.pcview_menu_header_unknown);
                break;
        }

        menu.setHeaderTitle(headerTitle);

        // Inflate the context menu
        if (computer.details.state == ComputerDetails.State.OFFLINE ||
            computer.details.state == ComputerDetails.State.UNKNOWN) {
            menu.add(Menu.NONE, WOL_ID, 1, getResources().getString(R.string.pcview_menu_send_wol));
            menu.add(Menu.NONE, GAMESTREAM_EOL_ID, 2, getResources().getString(R.string.pcview_menu_eol));
        }
        else if (computer.details.pairState != PairState.PAIRED) {
            menu.add(Menu.NONE, PAIR_ID_OTP, 1, getResources().getString(R.string.pcview_menu_pair_pc_otp));
            menu.add(Menu.NONE, PAIR_ID, 2, getResources().getString(R.string.pcview_menu_pair_pc));
            if (computer.details.nvidiaServer) {
                menu.add(Menu.NONE, GAMESTREAM_EOL_ID, 3, getResources().getString(R.string.pcview_menu_eol));
            } else {
                menu.add(Menu.NONE, OPEN_MANAGEMENT_PAGE_ID, 3, getResources().getString(R.string.pcview_menu_open_management_page));
            }
        }
        else {
            if (computer.details.runningGameId != 0) {
                menu.add(Menu.NONE, RESUME_ID, 1, getResources().getString(R.string.applist_menu_resume));
                menu.add(Menu.NONE, QUIT_ID, 2, getResources().getString(R.string.applist_menu_quit));
            }

            if (computer.details.nvidiaServer) {
                menu.add(Menu.NONE, GAMESTREAM_EOL_ID, 3, getResources().getString(R.string.pcview_menu_eol));
            } else {
                menu.add(Menu.NONE, OPEN_MANAGEMENT_PAGE_ID, 3, getResources().getString(R.string.pcview_menu_open_management_page));
            }

            menu.add(Menu.NONE, FULL_APP_LIST_ID, 4, getResources().getString(R.string.pcview_menu_app_list));
        }

        menu.add(Menu.NONE, TEST_NETWORK_ID, 5, getResources().getString(R.string.pcview_menu_test_network));
        menu.add(Menu.NONE, DELETE_ID, 6, getResources().getString(R.string.pcview_menu_delete_pc));
        menu.add(Menu.NONE, VIEW_DETAILS_ID, 7,  getResources().getString(R.string.pcview_menu_details));
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        // For some reason, this gets called again _after_ onPause() is called on this activity.
        // startComputerUpdates() manages this and won't actual start polling until the activity
        // returns to the foreground.
        startComputerUpdates();
    }

    private void showComputerMenu(ComputerObject computer) {
        if (computer == null) return;
        stopComputerUpdates(false);
        List<Integer> actions = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        ComputerDetails details = computer.details;
        if (details.state == ComputerDetails.State.OFFLINE || details.state == ComputerDetails.State.UNKNOWN) {
            addPcMenuAction(actions, labels, WOL_ID, R.string.pcview_menu_send_wol);
            addPcMenuAction(actions, labels, GAMESTREAM_EOL_ID, R.string.pcview_menu_eol);
        } else if (details.pairState != PairState.PAIRED) {
            addPcMenuAction(actions, labels, PAIR_ID_OTP, R.string.pcview_menu_pair_pc_otp);
            addPcMenuAction(actions, labels, PAIR_ID, R.string.pcview_menu_pair_pc);
            addPcMenuAction(actions, labels, details.nvidiaServer ? GAMESTREAM_EOL_ID : OPEN_MANAGEMENT_PAGE_ID,
                    details.nvidiaServer ? R.string.pcview_menu_eol : R.string.pcview_menu_open_management_page);
        } else {
            if (details.runningGameId != 0) {
                addPcMenuAction(actions, labels, RESUME_ID, R.string.applist_menu_resume);
                addPcMenuAction(actions, labels, QUIT_ID, R.string.applist_menu_quit);
            }
            addPcMenuAction(actions, labels, details.nvidiaServer ? GAMESTREAM_EOL_ID : OPEN_MANAGEMENT_PAGE_ID,
                    details.nvidiaServer ? R.string.pcview_menu_eol : R.string.pcview_menu_open_management_page);
            addPcMenuAction(actions, labels, FULL_APP_LIST_ID, R.string.pcview_menu_app_list);
        }
        addPcMenuAction(actions, labels, TEST_NETWORK_ID, R.string.pcview_menu_test_network);
        if (details.state == ComputerDetails.State.ONLINE && details.serverCert != null) {
            addPcMenuAction(actions, labels, SPEED_TEST_ID, R.string.pcview_menu_speed_test);
        }
        if (details.serverCert != null) addPcMenuAction(actions, labels, BACKUP_PAIRING_ID, R.string.pcview_menu_backup_pairing);
        addPcMenuAction(actions, labels, RESTORE_PAIRING_ID, R.string.pcview_menu_restore_pairing);
        addPcMenuAction(actions, labels, DELETE_ID, R.string.pcview_menu_delete_pc);
        addPcMenuAction(actions, labels, VIEW_DETAILS_ID, R.string.pcview_menu_details);

        String state = details.state == ComputerDetails.State.ONLINE ? getString(R.string.pcview_menu_header_online) :
                details.state == ComputerDetails.State.OFFLINE ? getString(R.string.pcview_menu_header_offline) :
                        getString(R.string.pcview_menu_header_unknown);
        CharSequence[] choices = labels.toArray(new CharSequence[0]);
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(details.name + "  •  " + state)
                .setItems(choices, (dialog, which) -> performComputerAction(computer, actions.get(which)));
        androidx.appcompat.app.AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(ignored -> startComputerUpdates());
        dialog.show();
    }

    private void addPcMenuAction(List<Integer> ids, List<String> labels, int id, int label) {
        ids.add(id);
        labels.add(getString(label));
    }

    private void doPair(final ComputerDetails computer, String otp, String passphrase) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.pair_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(PcView.this, getResources().getString(R.string.pairing), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                boolean success = false;
                try {
                    // Stop updates and wait while pairing
                    stopComputerUpdates(true);

                    httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                            computer.httpsPort, managerBinder.getUniqueId(), computer.serverCert,
                            PlatformBinding.getCryptoProvider(PcView.this));
                    if (httpConn.getPairState() == PairState.PAIRED) {
                        // Don't display any toast, but open the app list
                        message = null;
                        success = true;
                    }
                    else {
                        String pinStr = otp;
                        if (pinStr == null) {
                            pinStr = PairingManager.generatePinString();
                        }

                        // Spin the dialog off in a thread because it blocks
                        if (passphrase == null) {
                            Dialog.displayDialog(PcView.this, getResources().getString(R.string.pair_pairing_title),
                                    getResources().getString(R.string.pair_pairing_msg)+" "+pinStr+"\n\n"+
                                            getResources().getString(R.string.pair_pairing_help), false);
                        } else {
                            Dialog.displayDialog(PcView.this, getResources().getString(R.string.pair_pairing_title),
                                    getResources().getString(R.string.pair_otp_pairing_msg)+"\n\n"+
                                            getResources().getString(R.string.pair_otp_pairing_help), false);
                        }

                        PairingManager pm = httpConn.getPairingManager();

                        PairState pairState = pm.pair(httpConn.getServerInfo(true), pinStr, passphrase);
                        if (pairState == PairState.PIN_WRONG) {
                            message = getResources().getString(R.string.pair_incorrect_pin);
                        }
                        else if (pairState == PairState.FAILED) {
                            if (computer.runningGameId != 0) {
                                message = getResources().getString(R.string.pair_pc_ingame);
                            }
                            else {
                                message = getResources().getString(R.string.pair_fail);
                            }
                        }
                        else if (pairState == PairState.ALREADY_IN_PROGRESS) {
                            message = getResources().getString(R.string.pair_already_in_progress);
                        }
                        else if (pairState == PairState.PAIRED) {
                            // Just navigate to the app view without displaying a toast
                            message = null;
                            success = true;

                            // Pin this certificate for later HTTPS use
                            managerBinder.getComputer(computer.uuid).serverCert = pm.getPairedCert();

                            // Invalidate reachability information after pairing to force
                            // a refresh before reading pair state again
                            managerBinder.invalidateStateForComputer(computer.uuid);
                        }
                        else {
                            // Should be no other values
                            message = null;
                        }
                    }
                } catch (UnknownHostException e) {
                    message = getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = getResources().getString(R.string.error_404);
                } catch (XmlPullParserException | IOException e) {
                    e.printStackTrace();
                    message = e.getMessage();
                }

                Dialog.closeDialogs();

                final String toastMessage = message;
                final boolean toastSuccess = success;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (toastMessage != null) {
                            Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                        }

                        if (toastSuccess) {
                            // Open the app list after a successful pairing attempt
                            doAppList(computer, true, false);
                        }
                        else {
                            // Start polling again if we're still in the foreground
                            startComputerUpdates();
                        }
                    }
                });
            }
        }).start();
    }

    private void doOTPPair(final ComputerDetails computer) {
        Context context = PcView.this;

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 40);

        final EditText otpInput = new EditText(context);
        otpInput.setHint("PIN");
        otpInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        otpInput.setFilters(new InputFilter[] { new InputFilter.LengthFilter(4) });

        final EditText passphraseInput = new EditText(context);
        passphraseInput.setHint(getString(R.string.pair_passphrase_hint));
        passphraseInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        layout.addView(otpInput);
        layout.addView(passphraseInput);

        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(context);
        dialogBuilder.setTitle(R.string.pcview_menu_pair_pc_otp);
        dialogBuilder.setView(layout);

        dialogBuilder.setPositiveButton(getString(R.string.proceed), null);

        dialogBuilder.setNegativeButton(getString(R.string.cancel), (dialog, which) -> dialog.dismiss());
        AlertDialog dialog = dialogBuilder.create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String pin = otpInput.getText().toString();
            String passphrase = passphraseInput.getText().toString();
            if (pin.length() != 4) {
                Toast.makeText(context, getString(R.string.pair_pin_length_msg), Toast.LENGTH_SHORT).show();
                return;
            }
            if (passphrase.length() < 4 ) {
                Toast.makeText(context, getString(R.string.pair_passphrase_length_msg), Toast.LENGTH_SHORT).show();
                return;
            }
            doPair(computer, pin, passphrase);
            dialog.dismiss(); // Manually dismiss the dialog if the input is valid
        });
    }

    private void doWakeOnLan(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.ONLINE) {
            Toast.makeText(PcView.this, getResources().getString(R.string.wol_pc_online), Toast.LENGTH_SHORT).show();
            return;
        }

        if (computer.macAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.wol_no_mac), Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                String message;
                try {
                    WakeOnLanSender.sendWolPacket(computer);
                    message = getResources().getString(R.string.wol_waking_msg);
                } catch (IOException e) {
                    message = getResources().getString(R.string.wol_fail);
                }

                final String toastMessage = message;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void doUnpair(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(PcView.this, getResources().getString(R.string.unpairing), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                try {
                    httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                            computer.httpsPort, managerBinder.getUniqueId(), computer.serverCert,
                            PlatformBinding.getCryptoProvider(PcView.this));
                    if (httpConn.getPairState() == PairState.PAIRED) {
                        httpConn.unpair();
                        if (httpConn.getPairState() == PairState.NOT_PAIRED) {
                            message = getResources().getString(R.string.unpair_success);
                        }
                        else {
                            message = getResources().getString(R.string.unpair_fail);
                        }
                    }
                    else {
                        message = getResources().getString(R.string.unpair_error);
                    }
                } catch (UnknownHostException e) {
                    message = getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = getResources().getString(R.string.error_404);
                } catch (XmlPullParserException | IOException e) {
                    message = e.getMessage();
                    e.printStackTrace();
                }

                final String toastMessage = message;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void doAppList(ComputerDetails computer, boolean newlyPaired, boolean showHiddenGames) {
        if (computer.state == ComputerDetails.State.OFFLINE) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Intent i = new Intent(this, AppView.class);
        i.putExtra(AppView.NAME_EXTRA, computer.name);
        i.putExtra(AppView.UUID_EXTRA, computer.uuid);
        i.putExtra(AppView.NEW_PAIR_EXTRA, newlyPaired);
        i.putExtra(AppView.SHOW_HIDDEN_APPS_EXTRA, showHiddenGames);
        startActivity(i);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
        final ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(info.position);
        return performComputerAction(computer, item.getItemId());
    }

    private boolean performComputerAction(final ComputerObject computer, int actionId) {
        switch (actionId) {
            case PAIR_ID:
                doPair(computer.details, null, null);
                return true;

            case PAIR_ID_OTP:
                doOTPPair(computer.details);
                return true;

            case UNPAIR_ID:
                doUnpair(computer.details);
                return true;

            case WOL_ID:
                doWakeOnLan(computer.details);
                return true;

            case DELETE_ID:
                if (ActivityManager.isUserAMonkey()) {
                    LimeLog.info("Ignoring delete PC request from monkey");
                    return true;
                }
                UiHelper.displayDeletePcConfirmationDialog(this, computer.details, new Runnable() {
                    @Override
                    public void run() {
                        if (managerBinder == null) {
                            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                            return;
                        }
                        removeComputer(computer.details);
                    }
                }, null);
                return true;

            case FULL_APP_LIST_ID:
                doAppList(computer.details, false, true);
                return true;

            case RESUME_ID:
                if (managerBinder == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                    return true;
                }

                ServerHelper.doStart(this, new NvApp("app", null, computer.details.runningGameId, false), computer.details, managerBinder, false);
                return true;

            case QUIT_ID:
                if (managerBinder == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                    return true;
                }

                // Display a confirmation dialog first
                UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                    @Override
                    public void run() {
                        ServerHelper.doQuit(PcView.this, computer.details,
                                new NvApp("app", null, 0, false), managerBinder, null);
                    }
                }, null);
                return true;

            case VIEW_DETAILS_ID:
                Dialog.displayDialog(PcView.this, getResources().getString(R.string.title_details), computer.details.toString(), false);
                return true;

            case TEST_NETWORK_ID:
                ServerHelper.doNetworkTest(PcView.this);
                return true;

            case SPEED_TEST_ID:
                doPcSpeedTest(computer);
                return true;

            case GAMESTREAM_EOL_ID:
                HelpLauncher.launchGameStreamEolFaq(PcView.this);
                return true;

            case OPEN_MANAGEMENT_PAGE_ID:
                String managementUrl = computer.guessManagementUrl();
                if (managementUrl == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.pcview_error_no_management_url), Toast.LENGTH_LONG).show();
                } else {
                    HelpLauncher.launchUrl(PcView.this, managementUrl);
                }
                return true;

            case BACKUP_PAIRING_ID:
                promptForBackupPassphrase(computer);
                return true;

            case RESTORE_PAIRING_ID:
                pendingRestoreComputerUuid = computer.details.uuid;
                Intent restoreIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                restoreIntent.addCategory(Intent.CATEGORY_OPENABLE);
                restoreIntent.setType("application/json");
                startActivityForResult(restoreIntent, RESTORE_PC_BACKUP_REQUEST);
                return true;

            default:
                return false;
        }
    }

    private interface PassphraseCallback { void onPassphrase(String passphrase); }

    private void promptForBackupPassphrase(ComputerObject computer) {
        final String computerUuid = computer.details.uuid;
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        int horizontalPadding = Math.round(24 * getResources().getDisplayMetrics().density);
        fields.setPadding(horizontalPadding, 0, horizontalPadding, 0);
        EditText passphrase = new EditText(this);
        passphrase.setSingleLine(true);
        passphrase.setHint(R.string.paired_backup_passphrase_hint);
        passphrase.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText confirm = new EditText(this);
        confirm.setSingleLine(true);
        confirm.setHint(R.string.paired_backup_confirm_hint);
        confirm.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        fields.addView(passphrase);
        fields.addView(confirm);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.paired_backup_create)
                .setMessage(R.string.paired_backup_passphrase_explanation)
                .setView(fields)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String value = passphrase.getText().toString();
                    if (value.length() < 12) {
                        Toast.makeText(this, R.string.paired_backup_passphrase_too_short, Toast.LENGTH_LONG).show();
                    } else if (!value.equals(confirm.getText().toString())) {
                        Toast.makeText(this, R.string.paired_backup_passphrase_mismatch, Toast.LENGTH_LONG).show();
                    } else {
                        pendingBackupPassphrase = value;
                        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("application/json");
                        intent.putExtra(Intent.EXTRA_TITLE, "PyroWave-" + computer.details.name.replaceAll("[^A-Za-z0-9._-]", "_") + "-pairing.pwb");
                        pendingBackupComputerUuid = computerUuid;
                        startActivityForResult(intent, CREATE_PC_BACKUP_REQUEST);
                    }
                }).show();
    }

    private void promptForRestorePassphrase(byte[] backup, String computerUuid) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.paired_backup_passphrase_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        LinearLayout container = new LinearLayout(this);
        container.setPadding(padding, 0, padding, 0);
        container.addView(input);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.paired_backup_restore)
                .setMessage(R.string.paired_restore_passphrase_explanation)
                .setView(container)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    try {
                        PairedComputerBackup.Preview preview = PairedComputerBackup.preview(backup, input.getText().toString());
                        if (!preview.containsComputer(computerUuid)) {
                            showPairedBackupError(new IOException("This backup does not contain the selected PC."));
                            return;
                        }
                        ComputerObject selected = findComputer(computerUuid);
                        String name = selected == null ? computerUuid : selected.details.name;
                        new MaterialAlertDialogBuilder(this)
                                .setTitle(R.string.paired_restore_confirm_title)
                                .setMessage(getString(R.string.paired_restore_one_pc_message, name))
                                .setNegativeButton(android.R.string.cancel, null)
                                .setPositiveButton(R.string.paired_restore_button, (confirmDialog, confirmWhich) -> {
                                    try {
                                        PairedComputerBackup.restore(this, preview, computerUuid);
                                        new MaterialAlertDialogBuilder(this)
                                                .setTitle(R.string.paired_restore_complete_title)
                                                .setMessage(R.string.paired_restore_restart_message)
                                                .setCancelable(false)
                                                .setPositiveButton(android.R.string.ok, (done, doneWhich) -> {
                                                    finishAffinity();
                                                    android.os.Process.killProcess(android.os.Process.myPid());
                                                }).show();
                                    } catch (Exception e) { showPairedBackupError(e); }
                                }).show();
                    } catch (Exception e) { showPairedBackupError(e); }
                }).show();
    }

    private ComputerObject findComputer(String uuid) {
        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject candidate = (ComputerObject) pcGridAdapter.getItem(i);
            if (candidate.details.uuid.equals(uuid)) return candidate;
        }
        return null;
    }

    private void showPairedBackupError(Exception e) {
        String message = e.getLocalizedMessage() == null ? e.getClass().getSimpleName() : e.getLocalizedMessage();
        new MaterialAlertDialogBuilder(this).setTitle(R.string.paired_backup_error_title)
                .setMessage(getString(R.string.paired_backup_error_message, message))
                .setPositiveButton(android.R.string.ok, null).show();
    }

    private void doPcSpeedTest(ComputerObject computer) {
        if (managerBinder == null) {
            Toast.makeText(this, R.string.error_manager_not_running, Toast.LENGTH_LONG).show();
            return;
        }
        if (computer.details.state != ComputerDetails.State.ONLINE || computer.details.activeAddress == null || computer.details.serverCert == null) {
            Toast.makeText(this, R.string.pair_pc_offline, Toast.LENGTH_LONG).show();
            return;
        }

        final ComputerDetails selectedPc = computer.details;
        final String pcName = selectedPc.name;
        SpinnerDialog progress = SpinnerDialog.displayDialog(this,
                getString(R.string.pcview_speed_test_title, pcName),
                getString(R.string.pcview_speed_test_waiting), false);
        new Thread(() -> {
            double mbps = 0;
            Exception failure = null;
            try {
                NvHTTP http = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(selectedPc),
                        selectedPc.httpsPort, managerBinder.getUniqueId(), selectedPc.serverCert,
                        PlatformBinding.getCryptoProvider(PcView.this));
                mbps = http.measureConnectionBandwidthMbps();
            } catch (Exception e) {
                failure = e;
            } finally {
                final double resultMbps = mbps;
                final Exception resultFailure = failure;
                runOnUiThread(() -> {
                    progress.dismiss();
                    if (isFinishing() || !inForeground) return;
                    if (resultFailure != null) {
                        String message = resultFailure.getLocalizedMessage();
                        if (message == null || message.trim().isEmpty()) message = resultFailure.getClass().getSimpleName();
                        new MaterialAlertDialogBuilder(PcView.this)
                                .setTitle(R.string.pcview_speed_test_failed_title)
                                .setMessage(getString(R.string.pcview_speed_test_failed_message, message))
                                .setPositiveButton(android.R.string.ok, null).show();
                    } else {
                        new MaterialAlertDialogBuilder(PcView.this)
                                .setTitle(getString(R.string.pcview_speed_test_title, pcName))
                                .setMessage(getString(R.string.pcview_speed_test_result,
                                        String.format(java.util.Locale.getDefault(), "%.1f", resultMbps)))
                                .setPositiveButton(android.R.string.ok, null).show();
                    }
                });
            }
        }, "PC-speed-test").start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CREATE_PC_BACKUP_REQUEST) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                try (OutputStream output = getContentResolver().openOutputStream(data.getData(), "wt")) {
                    if (output == null) throw new IOException("Could not write to the selected backup location");
                    output.write(PairedComputerBackup.create(this, pendingBackupPassphrase, pendingBackupComputerUuid));
                    Toast.makeText(this, R.string.paired_backup_saved, Toast.LENGTH_LONG).show();
                } catch (Exception e) { showPairedBackupError(e); }
            }
            pendingBackupPassphrase = null;
            pendingBackupComputerUuid = null;
            return;
        }
        if (requestCode == RESTORE_PC_BACKUP_REQUEST && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            String uuid = pendingRestoreComputerUuid;
            pendingRestoreComputerUuid = null;
            try (InputStream input = getContentResolver().openInputStream(data.getData());
                 java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                if (input == null) throw new IOException("Could not read the selected backup");
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (output.size() + read > 16 * 1024 * 1024) throw new IOException("Backup file is unexpectedly large");
                    output.write(buffer, 0, read);
                }
                promptForRestorePassphrase(output.toByteArray(), uuid);
            } catch (Exception e) { showPairedBackupError(e); }
        }
    }

    private void removeComputer(ComputerDetails details) {
        managerBinder.removeComputer(details);

        new DiskAssetLoader(this).deleteAssetsForComputer(details.uuid);

        // Delete hidden games preference value
        getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .remove(details.uuid)
                .apply();

        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(i);

            if (details.equals(computer.details)) {
                // Disable or delete shortcuts referencing this PC
                shortcutHelper.disableComputerShortcut(details,
                        getResources().getString(R.string.scut_deleted_pc));

                pcGridAdapter.removeComputer(computer);
                pcGridAdapter.notifyDataSetChanged();

                if (pcGridAdapter.getCount() == 0) {
                    // Show the "Discovery in progress" view
                    noPcFoundLayout.setVisibility(View.VISIBLE);
                }

                break;
            }
        }
    }

    private void updateComputer(ComputerDetails details) {
        ComputerObject existingEntry = null;

        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(i);

            // Check if this is the same computer
            if (details.uuid.equals(computer.details.uuid)) {
                existingEntry = computer;
                break;
            }
        }

        if (existingEntry != null) {
            // Replace the information in the existing entry
            existingEntry.details = details;
        }
        else {
            // Add a new entry
            pcGridAdapter.addComputer(new ComputerObject(details));

            // Remove the "Discovery in progress" view
            noPcFoundLayout.setVisibility(View.INVISIBLE);
        }

        // Notify the view that the data has changed
        pcGridAdapter.notifyDataSetChanged();
    }

    @Override
    public int getAdapterFragmentLayoutId() {
        return R.layout.pc_grid_view;
    }

    @Override
    public void receiveAbsListView(AbsListView listView) {
        listView.setAdapter(pcGridAdapter);
        listView.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> arg0, View arg1, int pos,
                                    long id) {
                ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(pos);
                if (computer.details.state == ComputerDetails.State.UNKNOWN ||
                    computer.details.state == ComputerDetails.State.OFFLINE) {
                    // Open the context menu if a PC is offline or refreshing
                    showComputerMenu(computer);
                } else if (computer.details.pairState != PairState.PAIRED) {
                    // Pair an unpaired machine by default
                    doPair(computer.details, null, null);
                } else {
                    doAppList(computer.details, false, false);
                }
            }
        });
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            showComputerMenu((ComputerObject) pcGridAdapter.getItem(position));
            return true;
        });
        UiHelper.applyStatusBarPadding(listView);
    }

    public static class ComputerObject {
        public ComputerDetails details;

        public ComputerObject(ComputerDetails details) {
            if (details == null) {
                throw new IllegalArgumentException("details must not be null");
            }
            this.details = details;
        }

        @Override
        public String toString() {
            return details.name;
        }
        public String guessManagementUrl() {
            if (details.activeAddress == null) return null;
            return "https://" + details.activeAddress.address + ":" + (details.guessExternalPort() + 1);
        }
    }
}

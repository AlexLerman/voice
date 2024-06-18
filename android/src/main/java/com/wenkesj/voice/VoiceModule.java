package com.wenkesj.voice;

import static android.media.AudioManager.GET_DEVICES_INPUTS;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.media.AudioManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHeadset;
import androidx.annotation.NonNull;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.modules.core.PermissionAwareActivity;
import com.facebook.react.modules.core.PermissionListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

public class VoiceModule extends ReactContextBaseJavaModule implements RecognitionListener {

    final ReactApplicationContext reactContext;
    private SpeechRecognizer speech = null;
    private boolean isRecognizing = false;
    private String locale = null;
    private AudioManager audioManager;

    public VoiceModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
        connectBT(reactContext);
    }

    @ReactMethod
    public void isBluetoothInputConnected(final Callback callback) {
        final VoiceModule self = this;
        Handler mainHandler = new Handler(this.reactContext.getMainLooper());
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Log.d("BTLE", "asking if bluetooth is connected");
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        audioManager = (AudioManager) reactContext.getSystemService(reactContext.AUDIO_SERVICE);
                        AudioDeviceInfo device = audioManager.getCommunicationDevice();
                        callback.invoke(device.isSource(), false);
                    }
                    callback.invoke(false, false);
                } catch(Exception e) {
                    callback.invoke(false, e.getMessage());
                }
            }
        });
    }

    private void findAndConnectBT(Boolean notify) {
        Log.d("BTLE", "find and connect headset");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager = (AudioManager) reactContext.getSystemService(reactContext.AUDIO_SERVICE);
            AudioDeviceInfo[] allDeviceInfo = audioManager.getDevices(GET_DEVICES_INPUTS);
            AudioDeviceInfo bleInputDevice = null;
            for (AudioDeviceInfo device : allDeviceInfo) {
                if (device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET || device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    bleInputDevice = device;
                    audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                    if (device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                        audioManager.setCommunicationDevice(bleInputDevice);
                        Log.d("BTLE", "Connecting to BLE headset from load");
                    } else {
                        audioManager.startBluetoothSco();
                        audioManager.setBluetoothScoOn(true);
                        Log.d("BTLE", "Connecting to SCO from load");
                    }
                    Log.d("BTLE", "Setting communitcation device from module loading");
                    if (notify) btConnected(true);
                    break;
                }
            }
        }
    }

    private void connectBT(ReactApplicationContext reactContext) {
        Log.d("BTLE", "Connecting when module loaded");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            findAndConnectBT(false);

            final AudioDeviceCallback audioDeviceCallback = new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                    Log.d("BTLE", "Device added");
                    findAndConnectBT(true);
                };

                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                    Log.d("BTLE", "Device removed");
                    audioManager = (AudioManager) reactContext.getSystemService(reactContext.AUDIO_SERVICE);
                    AudioDeviceInfo bleInputDevice = null;
                    for (AudioDeviceInfo device : removedDevices) {
                        if (device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET || device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                            bleInputDevice = device;
                            audioManager.setMode(AudioManager.MODE_NORMAL);
                            if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                                audioManager.stopBluetoothSco();
                                audioManager.setBluetoothScoOn(false);
                            } else {
                                audioManager.clearCommunicationDevice();
                            }
                            btConnected(false);
                        }
                    }
                };
            };
            Log.d("BTLE", "Registering callback");
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, null);
        }
    }

    private void btConnected(Boolean connected) {
        WritableMap error = Arguments.createMap();
        WritableMap event = Arguments.createMap();
        if (connected){
            error.putString("message", "CONNECTED");
            error.putString("code", String.valueOf(1));
            event.putMap("event", error);
            sendEvent("BTCONNECT", event);
        } else {
            error.putString("message", "DISCONNECTED");
            error.putString("code", String.valueOf(0));
            event.putMap("event", error);
            sendEvent("BTCONNECT", event);
        }
    }

    private String getLocale(String locale) {
        if (locale != null && !locale.equals("")) {
            return locale;
        }

        return Locale.getDefault().toString();
    }

    private void startListening(ReadableMap opts) {
//        findAndConnectBT(true);
        if (speech != null) {
            speech.destroy();
            speech = null;
        }

        if(opts.hasKey("RECOGNIZER_ENGINE")) {
            switch (opts.getString("RECOGNIZER_ENGINE")) {
                case "GOOGLE": {
                    speech = SpeechRecognizer.createSpeechRecognizer(this.reactContext, ComponentName.unflattenFromString("com.google.android.googlequicksearchbox/com.google.android.voicesearch.serviceapi.GoogleRecognitionService"));
                    break;
                }
                default:
                    speech = SpeechRecognizer.createSpeechRecognizer(this.reactContext);
            }
        } else {
            speech = SpeechRecognizer.createSpeechRecognizer(this.reactContext);
        }

        speech.setRecognitionListener(this);

        final Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);

        // Load the intent with options from JS
        ReadableMapKeySetIterator iterator = opts.keySetIterator();
        while (iterator.hasNextKey()) {
            String key = iterator.nextKey();
            switch (key) {
                case "EXTRA_LANGUAGE_MODEL":
                    switch (opts.getString(key)) {
                        case "LANGUAGE_MODEL_FREE_FORM":
                            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                            break;
                        case "LANGUAGE_MODEL_WEB_SEARCH":
                            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH);
                            break;
                        default:
                            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                            break;
                    }
                    break;
                case "EXTRA_MAX_RESULTS": {
                    Double extras = opts.getDouble(key);
                    intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, extras.intValue());
                    break;
                }
                case "EXTRA_PARTIAL_RESULTS": {
                    intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, opts.getBoolean(key));
                    break;
                }
                case "EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS": {
                    Double extras = opts.getDouble(key);
                    intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, extras.intValue());
                    break;
                }
                case "EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS": {
                    Double extras = opts.getDouble(key);
                    intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, extras.intValue());
                    break;
                }
                case "EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS": {
                    Double extras = opts.getDouble(key);
                    intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, extras.intValue());
                    break;
                }
                case "EXTRA_SEGMENTED_SESSION" : {
                    if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS);
                    } else {
                        Log.e("API Version", "API version too low. Does not support segmented sessions");
                    }
                    break;
                }
            }
        }
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, getLocale(this.locale));
        speech.startListening(intent);
    }

    private void startSpeechWithPermissions(final String locale, final ReadableMap opts, final Callback callback) {
        this.locale = locale;

        Handler mainHandler = new Handler(this.reactContext.getMainLooper());
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    startListening(opts);
                    isRecognizing = true;
                    callback.invoke(false);
                } catch (Exception e) {
                    callback.invoke(e.getMessage());
                }
            }
        });
    }

    @Override
    public String getName() {
        return "RCTVoice";
    }

    @ReactMethod
    public void startSpeech(final String locale, final ReadableMap opts, final Callback callback) {
        if (!isPermissionGranted() && opts.getBoolean("REQUEST_PERMISSIONS_AUTO")) {
            String[] PERMISSIONS = {Manifest.permission.RECORD_AUDIO};
            if (this.getCurrentActivity() != null) {
                ((PermissionAwareActivity) this.getCurrentActivity()).requestPermissions(PERMISSIONS, 1, new PermissionListener() {
                    public boolean onRequestPermissionsResult(final int requestCode,
                                                              @NonNull final String[] permissions,
                                                              @NonNull final int[] grantResults) {
                        boolean permissionsGranted = true;
                        for (int i = 0; i < permissions.length; i++) {
                            final boolean granted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
                            permissionsGranted = permissionsGranted && granted;
                        }
                        startSpeechWithPermissions(locale, opts, callback);
                        return permissionsGranted;
                    }
                });
            }
            return;
        }
        startSpeechWithPermissions(locale, opts, callback);
    }

    @ReactMethod
    public void stopSpeech(final Callback callback) {
        Handler mainHandler = new Handler(this.reactContext.getMainLooper());
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (speech != null) {
                        speech.stopListening();
                    }
                    isRecognizing = false;
                    callback.invoke(false);
                } catch(Exception e) {
                    callback.invoke(e.getMessage());
                }
            }
        });
    }

    @ReactMethod
    public void cancelSpeech(final Callback callback) {
        Handler mainHandler = new Handler(this.reactContext.getMainLooper());
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (speech != null) {
                        speech.cancel();
                    }
                    isRecognizing = false;
                    callback.invoke(false);
                } catch(Exception e) {
                    callback.invoke(e.getMessage());
                }
            }
        });
    }

    @ReactMethod
    public void destroySpeech(final Callback callback) {
        Handler mainHandler = new Handler(this.reactContext.getMainLooper());
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (speech != null) {
                        speech.destroy();
                    }
                    speech = null;
                    isRecognizing = false;
                    callback.invoke(false);
                } catch(Exception e) {
                    callback.invoke(e.getMessage());
                }
            }
        });
    }

    @ReactMethod
    public void isSpeechAvailable(final Callback callback) {
        final VoiceModule self = this;
        Handler mainHandler = new Handler(this.reactContext.getMainLooper());
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Boolean isSpeechAvailable = SpeechRecognizer.isRecognitionAvailable(self.reactContext);
                    callback.invoke(isSpeechAvailable, false);
                } catch(Exception e) {
                    callback.invoke(false, e.getMessage());
                }
            }
        });
    }

    @ReactMethod
    public void getSpeechRecognitionServices(Promise promise) {
        final List<ResolveInfo> services = this.reactContext.getPackageManager()
                .queryIntentServices(new Intent(RecognitionService.SERVICE_INTERFACE), 0);
        WritableArray serviceNames = Arguments.createArray();
        for (ResolveInfo service : services) {
            serviceNames.pushString(service.serviceInfo.packageName);
        }

        promise.resolve(serviceNames);
    }

    public static boolean isBluetoothHeadsetConnected() {
        BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        return mBluetoothAdapter != null && mBluetoothAdapter.isEnabled()
                && mBluetoothAdapter.getProfileConnectionState(BluetoothHeadset.HEADSET) == BluetoothHeadset.STATE_CONNECTED;
    }

    private boolean isPermissionGranted() {
        String permission = Manifest.permission.RECORD_AUDIO;
        int res = getReactApplicationContext().checkCallingOrSelfPermission(permission);
        return res == PackageManager.PERMISSION_GRANTED;
    }

    @ReactMethod
    public void isRecognizing(Callback callback) {
        callback.invoke(isRecognizing);
    }

    private void sendEvent(String eventName, @Nullable WritableMap params) {
        this.reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(eventName, params);
    }

    @Override
    public void onBeginningOfSpeech() {
        WritableMap event = Arguments.createMap();
        event.putBoolean("error", false);
        sendEvent("onSpeechStart", event);
        Log.d("ASR", "onBeginningOfSpeech()");
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
        WritableMap event = Arguments.createMap();
        event.putBoolean("error", false);
        sendEvent("onSpeechRecognized", event);
        Log.d("ASR", "onBufferReceived()");
    }

    @Override
    public void onEndOfSpeech() {
        WritableMap event = Arguments.createMap();
        event.putBoolean("error", false);
        sendEvent("onSpeechEnd", event);
        Log.d("ASR", "onEndOfSpeech()");
        isRecognizing = false;
    }

    @Override
    public void onEndOfSegmentedSession() {
        WritableMap event = Arguments.createMap();
        event.putBoolean("error", false);
        sendEvent("onEndOfSegmentedSession", event);
        Log.d("ASR", "onEndOfSegmentedSession()");
        isRecognizing = false;
    }

    @Override
    public void onError(int errorCode) {
        String errorMessage = String.format("%d/%s", errorCode, getErrorText(errorCode));
        WritableMap error = Arguments.createMap();
        error.putString("message", errorMessage);
        error.putString("code", String.valueOf(errorCode));
        WritableMap event = Arguments.createMap();
        event.putMap("error", error);
        sendEvent("onSpeechError", event);
        Log.d("ASR", "onError() - " + errorMessage);
    }

    @Override
    public void onEvent(int arg0, Bundle arg1) { }

    @Override
    public void onPartialResults(Bundle results) {
        WritableArray arr = Arguments.createArray();

        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);

        if (matches != null) {
            for (String result : matches) {
                arr.pushString(result);
            }
        }

        WritableMap event = Arguments.createMap();
        event.putArray("value", arr);
        sendEvent("onSpeechPartialResults", event);
        Log.d("ASR", "onPartialResults()");
    }

    @Override
    public void onSegmentResults(Bundle results) {
        WritableArray arr = Arguments.createArray();

        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);

        if (matches != null) {
            for (String result : matches) {
                arr.pushString(result);
            }
        }
        WritableMap event = Arguments.createMap();
        event.putArray("value", arr);
        sendEvent("onSpeechSegmentResults", event);
        Log.d("ASR", "onSegmentResults()");
    }


    @Override
    public void onReadyForSpeech(Bundle arg0) {
        WritableMap event = Arguments.createMap();
        event.putBoolean("error", false);
        sendEvent("onSpeechStart", event);
        Log.d("ASR", "onReadyForSpeech()");
    }

    @Override
    public void onResults(Bundle results) {
        WritableArray arr = Arguments.createArray();

        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null) {
            for (String result : matches) {
                arr.pushString(result);
            }
        }

        WritableMap event = Arguments.createMap();
        event.putArray("value", arr);
        sendEvent("onSpeechResults", event);
        Log.d("ASR", "onResults()");
    }

    @Override
    public void onRmsChanged(float rmsdB) {
        WritableMap event = Arguments.createMap();
        event.putDouble("value", (double) rmsdB);
        sendEvent("onSpeechVolumeChanged", event);
    }

    public static String getErrorText(int errorCode) {
        String message;
        switch (errorCode) {
            case SpeechRecognizer.ERROR_AUDIO:
                message = "Audio recording error";
                break;
            case SpeechRecognizer.ERROR_CLIENT:
                message = "Client side error";
                break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                message = "Insufficient permissions";
                break;
            case SpeechRecognizer.ERROR_NETWORK:
                message = "Network error";
                break;
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                message = "Network timeout";
                break;
            case SpeechRecognizer.ERROR_NO_MATCH:
                message = "No match";
                break;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                message = "RecognitionService busy";
                break;
            case SpeechRecognizer.ERROR_SERVER:
                message = "error from server";
                break;
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                message = "No speech input";
                break;
            default:
                message = "Didn't understand, please try again.";
                break;
        }
        return message;
    }
}
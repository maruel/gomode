// Supplies platform speech readiness without a downloaded ASR model in emulator tests.
package com.fghbuild.gomode.voice;

import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;

// This service runs in the test APK's own process, which cannot load the target APK's Kotlin runtime.
public final class TestSpeechRecognitionService extends RecognitionService {
    @Override
    protected void onStartListening(Intent recognizerIntent, Callback listener) {
        try {
            listener.readyForSpeech(new Bundle());
        } catch (android.os.RemoteException failure) {
            throw new IllegalStateException("Speech client disconnected before readiness", failure);
        }
    }

    @Override
    protected void onStopListening(Callback listener) {
        Bundle result = new Bundle();
        result.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, new ArrayList<>());
        try {
            listener.results(result);
        } catch (android.os.RemoteException failure) {
            throw new IllegalStateException("Speech client disconnected before results", failure);
        }
    }

    @Override
    protected void onCancel(Callback listener) {}
}

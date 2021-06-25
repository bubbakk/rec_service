package com.bubbakk.rec_service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;

import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import javax.xml.transform.sax.TemplatesHandler;

public class RecService extends Service {
    public static final String CHANNEL_ID = "ForegroundServiceChannel";

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent.hasExtra("mute")){
            Bundle b=new Bundle();
            b=intent.getExtras();
            SetMuted(b.getBoolean("mute"));
            return START_NOT_STICKY;
        }
        if (intent.hasExtra("pause")){
            Bundle b=new Bundle();
            b=intent.getExtras();
            SetPaused(b.getBoolean("pause"));
            return START_NOT_STICKY;
        }
        if (intent.hasExtra("wholeRecPath")){
            Bundle b=new Bundle();
            b=intent.getExtras();
            setWholeRecPath(b.getString("wholeRecPath"));
            //return START_NOT_STICKY;
        }
        if (intent.hasExtra("chunksPath")){
            Bundle b=new Bundle();
            b=intent.getExtras();
            setChunksPath(b.getString("chunksPath"));
            //return START_NOT_STICKY;
        }
        if (intent.hasExtra("prefix")){
            Bundle b=new Bundle();
            b=intent.getExtras();
            SetPrefix(b.getString("prefix"));
            //return START_NOT_STICKY;
        }
        if (intent.hasExtra("chunkSize")){
            Bundle b=new Bundle();
            b=intent.getExtras();
            SetChunkSize(b.getInt("chunkSize"));
            //return START_NOT_STICKY;
        }
        if (intent.hasExtra("alsoWholeRec")){
            Bundle b=new Bundle();
            b=intent.getExtras();
            SetAlsoWholeRec(b.getBoolean("alsoWholeRec"));
            //return START_NOT_STICKY;
        }

        String input = intent.getStringExtra("inputExtra");
        createNotificationChannel();
        Intent notificationIntent = new Intent(this, RecServicePlugin.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, 0);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Foreground Service")
                .setContentText(input)
               // .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .build();

        startForeground(1, notification);

        // do heavy work on a background thread
        StartRecorder();

        //stopSelf();

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        StopRecorder();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );

            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    private static String TAG = "RecService";

    // the audio recording options
    private static final int RECORDING_RATE = 44100;
    private static final int CHANNEL = AudioFormat.CHANNEL_IN_MONO;
    private static final int FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    // the audio recorder
    private AudioRecord recorder;
    // the minimum buffer size needed for audio recording
    private static int BUFFER_SIZE = AudioRecord.getMinBufferSize(RECORDING_RATE, CHANNEL, FORMAT);
    // are we currently sending audio data
    private boolean currentlySendingAudio = false;

    FileOutputStream outputStream = null;
    FileOutputStream outputStreamWhole = null;

    private boolean muted = false;
    private boolean paused = false;
    private String prefix = null;
    private int chunkSize = 0;
    private  boolean alsoWholeRec = false;

    private int totalWritten = 0;
    private int chunkNum = 1;

    private String filename;
    private String filenameWhole;
    private String chunksFilePath;
    private String wholeRecPath;

    public void SetMuted(boolean m){
        muted = m;
    }

    public void SetPaused(boolean p){
        paused = p;
    }

    public void setChunksPath(String p){
        chunksFilePath = p;
    }

    public void setWholeRecPath(String p){
        wholeRecPath = p;
    }

    public void SetPrefix(String p){
        prefix = p;
    }
    public void SetChunkSize(int s){
        chunkSize = s;
    }

    public void  SetAlsoWholeRec(boolean b) {
        alsoWholeRec = b;
    }

    public void StartRecorder() {
        Log.i(TAG, "Starting the audio stream");
        currentlySendingAudio = true;
        startStreaming();
    }

    public void StopRecorder() {
        Log.i(TAG, "Stopping the audio stream");
        currentlySendingAudio = false;
        recorder.release();
    }

    private void startStreaming() {
        Log.i(TAG, "Starting the background thread (in this foreground service) to read the audio data");

        if(chunksFilePath == null || chunksFilePath.isEmpty())
            chunksFilePath = Environment.getExternalStorageDirectory().getPath();
        if(wholeRecPath == null || wholeRecPath.isEmpty())
            wholeRecPath = Environment.getExternalStorageDirectory().getPath();

        filename = prefix != null && !prefix.isEmpty()
            ? chunkSize > 0
                ? chunksFilePath + "/" + prefix + "~" + String.format("%04d", chunkNum) + ".wav"          // four digits decimal max = 9999 = 10.000 chunks = ~6 days, one slice per minute
                : chunksFilePath + "/" + prefix + ".wav"
            : chunksFilePath + "/record.wav";

        if(alsoWholeRec)
            filenameWhole = prefix != null && !prefix.isEmpty()
                ? wholeRecPath + "/" + prefix + "-whole.wav"
                : wholeRecPath + "/record-whole.wav";

        try {
            outputStream = new FileOutputStream(filename);
            if(alsoWholeRec)
                outputStreamWhole = new FileOutputStream(filenameWhole);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        Thread streamThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.d(TAG, "Creating the buffer of size " + BUFFER_SIZE);
                    //byte[] buffer = new byte[BUFFER_SIZE];
                    //int rate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_SYSTEM);
                    int rate = 16000;
                    int bufferSize = 2 * AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    //short[] buffer = new short[bufferSize]; //AudioFormat.ENCODING_PCM_16BIT
                    byte[] buffer = new byte[bufferSize]; //AudioFormat.ENCODING_PCM_8BIT

                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);

                    Log.d(TAG, "Creating the AudioRecord");
                    //recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, RECORDING_RATE, CHANNEL, FORMAT, BUFFER_SIZE * 10);
                    recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);

                    // Write out the wav file header
                    writeWavHeader(outputStream, AudioFormat.CHANNEL_IN_MONO, rate, AudioFormat.ENCODING_PCM_16BIT);
                    if(alsoWholeRec)
                        writeWavHeader(outputStreamWhole, AudioFormat.CHANNEL_IN_MONO, rate, AudioFormat.ENCODING_PCM_16BIT);

                    //byte[] bytes = new byte[buffer.length * 2];

                    Log.d(TAG, "AudioRecord recording...");
                    recorder.startRecording();

                    while (currentlySendingAudio == true) {
                        // read the data into the buffer
                        int readSize = recorder.read(buffer, 0, buffer.length);
                        if(totalWritten + readSize > chunkSize*1024*1024){
                            ///chiudere il file corrente ed aprire un nuovo outputStream!
                            Log.d(TAG, "Closing Chunk " + chunkNum);
                            try {
                                outputStream.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            updateWavHeader(new File(filename));

                            //aprire nuovo stream:
                            totalWritten = 0;
                            chunkNum++;

                            filename = prefix != null && !prefix.isEmpty()
                                    ? chunkSize > 0
                                    ? chunksFilePath + "/" + prefix + "~" + String.format("%04d", chunkNum) + ".wav"
                                    : chunksFilePath + "/" + prefix + ".wav"
                                    : chunksFilePath + "/record.wav";

                            try {
                                outputStream = new FileOutputStream(filename);

                            } catch (FileNotFoundException e) {
                                e.printStackTrace();
                            }
                            writeWavHeader(outputStream, AudioFormat.CHANNEL_IN_MONO, rate, AudioFormat.ENCODING_PCM_16BIT);

                        }

                        try {
                            //ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asShortBuffer().put(buffer);
                            if(muted){
                                Arrays.fill(buffer, (byte) 0);
                            }
                            if(!paused) {
                                outputStream.write(buffer, 0, readSize);
                                if(alsoWholeRec)
                                    outputStreamWhole.write(buffer, 0, readSize);
                                totalWritten += readSize;
                            }

                        } catch (IOException e) {
                            e.printStackTrace();
                        }

                        /*
                        double maxAmplitude = 0;
                        for (int i = 0; i < readSize; i++) {
                            if (Math.abs(buffer[i]) > maxAmplitude) {
                                maxAmplitude = Math.abs(buffer[i]);
                            }
                        }

                        double db = 0;
                        if (maxAmplitude != 0) {
                            db = 20.0 * Math.log10(maxAmplitude / 32767.0) + 90;
                        }

                        Log.d(TAG, "Max amplitude: " + maxAmplitude + " ; DB: " + db);

                         */
                    }

                    Log.d(TAG, "AudioRecord finished recording");
                    try {
                        outputStream.close();
                        if(alsoWholeRec)
                            outputStreamWhole.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    updateWavHeader(new File(filename));
                    if(alsoWholeRec)
                        updateWavHeader(new File(filenameWhole));

                } catch (Exception e) {
                    Log.e(TAG, "Exception: " + e);
                }
            }
        });

        // start the thread
        streamThread.start();
    }


    /**
     * Writes the proper 44-byte RIFF/WAVE header to/for the given stream
     * Two size fields are left empty/null since we do not yet know the final stream size
     *
     * @param out         The stream to write the header to
     * @param channelMask An AudioFormat.CHANNEL_* mask
     * @param sampleRate  The sample rate in hertz
     * @param encoding    An AudioFormat.ENCODING_PCM_* value
     * @throws IOException
     */
    private static void writeWavHeader(OutputStream out, int channelMask, int sampleRate, int encoding) throws IOException {
        short channels;
        switch (channelMask) {
            case AudioFormat.CHANNEL_IN_MONO:
                channels = 1;
                break;
            case AudioFormat.CHANNEL_IN_STEREO:
                channels = 2;
                break;
            default:
                throw new IllegalArgumentException("Unacceptable channel mask");
        }

        short bitDepth;
        switch (encoding) {
            case AudioFormat.ENCODING_PCM_8BIT:
                bitDepth = 8;
                break;
            case AudioFormat.ENCODING_PCM_16BIT:
                bitDepth = 16;
                break;
            case AudioFormat.ENCODING_PCM_FLOAT:
                bitDepth = 32;
                break;
            default:
                throw new IllegalArgumentException("Unacceptable encoding");
        }

        writeWavHeader(out, channels, sampleRate, bitDepth);
    }

    /**
     * Writes the proper 44-byte RIFF/WAVE header to/for the given stream
     * Two size fields are left empty/null since we do not yet know the final stream size
     *
     * @param out        The stream to write the header to
     * @param channels   The number of channels
     * @param sampleRate The sample rate in hertz
     * @param bitDepth   The bit depth
     * @throws IOException
     */
    private static void writeWavHeader(OutputStream out, short channels, int sampleRate, short bitDepth) throws IOException {
        // Convert the multi-byte integers to raw bytes in little endian format as required by the spec
        byte[] littleBytes = ByteBuffer
                .allocate(14)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(channels)
                .putInt(sampleRate)
                .putInt(sampleRate * channels * (bitDepth / 8))
                .putShort((short) (channels * (bitDepth / 8)))
                .putShort(bitDepth)
                .array();

        // Not necessarily the best, but it's very easy to visualize this way
        out.write(new byte[]{
                // RIFF header
                'R', 'I', 'F', 'F', // ChunkID
                0, 0, 0, 0, // ChunkSize (must be updated later)
                'W', 'A', 'V', 'E', // Format
                // fmt subchunk
                'f', 'm', 't', ' ', // Subchunk1ID
                16, 0, 0, 0, // Subchunk1Size
                1, 0, // AudioFormat
                littleBytes[0], littleBytes[1], // NumChannels
                littleBytes[2], littleBytes[3], littleBytes[4], littleBytes[5], // SampleRate
                littleBytes[6], littleBytes[7], littleBytes[8], littleBytes[9], // ByteRate
                littleBytes[10], littleBytes[11], // BlockAlign
                littleBytes[12], littleBytes[13], // BitsPerSample
                // data subchunk
                'd', 'a', 't', 'a', // Subchunk2ID
                0, 0, 0, 0, // Subchunk2Size (must be updated later)
        });
    }

    /**
     * Updates the given wav file's header to include the final chunk sizes
     *
     * @param wav The wav file to update
     * @throws IOException
     */
    private static void updateWavHeader(File wav) throws IOException {
        byte[] sizes = ByteBuffer
                .allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                // There are probably a bunch of different/better ways to calculate
                // these two given your circumstances. Cast should be safe since if the WAV is
                // > 4 GB we've already made a terrible mistake.
                .putInt((int) (wav.length() - 8)) // ChunkSize
                .putInt((int) (wav.length() - 44)) // Subchunk2Size
                .array();

        RandomAccessFile accessWave = null;
        //noinspection CaughtExceptionImmediatelyRethrown
        try {
            accessWave = new RandomAccessFile(wav, "rw");
            // ChunkSize
            accessWave.seek(4);
            accessWave.write(sizes, 0, 4);

            // Subchunk2Size
            accessWave.seek(40);
            accessWave.write(sizes, 4, 4);
        } catch (IOException ex) {
            // Rethrow but we still close accessWave in our finally
            throw ex;
        } finally {
            if (accessWave != null) {
                try {
                    accessWave.close();
                } catch (IOException ex) {
                    //
                }
            }
        }
    }

}


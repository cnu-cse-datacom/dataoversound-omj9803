package com.example.sound.devicesound;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.transform.*;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import static java.lang.Math.abs;

public class Listentone {

    int HANDSHAKE_START_HZ = 4096;
    int HANDSHAKE_END_HZ = 5120 + 1024;

    int START_HZ = 1024;
    int STEP_HZ = 256;
    int BITS = 4;

    int FEC_BYTES = 4;

    private int mAudioSource = MediaRecorder.AudioSource.MIC;
    private int mSampleRate = 44100;
    private int mChannelCount = AudioFormat.CHANNEL_IN_MONO;
    private int mAudioFormat = AudioFormat.ENCODING_PCM_16BIT;
    private float interval = 0.1f;

    private int mBufferSize = AudioRecord.getMinBufferSize(mSampleRate, mChannelCount, mAudioFormat);

    public AudioRecord mAudioRecord = null;
    int audioEncodig;
    boolean startFlag;
    FastFourierTransformer transform;


    public Listentone(){

        // 푸리에변환 : 시간(t)을 주파수(f)로 변환해준다.
        // STANDARD 정규화 규칙을 생성자에 전달한 FastFourierTransformer의 인스턴스 생성

        transform = new FastFourierTransformer(DftNormalization.STANDARD);
        startFlag = false;
        mAudioRecord = new AudioRecord(mAudioSource, mSampleRate, mChannelCount, mAudioFormat, mBufferSize);
        mAudioRecord.startRecording();

    }

    public void PreRequest() {
        Queue<Integer> packet = new LinkedList<>();
        boolean in_packet = false;
        // recoder로부터 음성을 읽는 코드
        // buffer 에 소리데이터가 들어감. buffer 이용하여 fourier(푸리에) transform하면됨.

        int blocksize = findPowerSize((int) (long) Math.round(interval / 2 * mSampleRate)); // round : 반올림
        short[] buffer = new short[blocksize];
        while(true){
            int bufferedReadResult = mAudioRecord.read(buffer, 0, blocksize);
            double[] packet_buffer = new double[buffer.length];

            // 입력받은 buffer을 int형에서 double형으로 변환
            for(int i = 0;i < buffer.length; i++){
                packet_buffer[i] = (double)buffer[i];
            }
            //Log.d("buffer: ", String.valueOf(packet_buffer[0]));

            int dom = (int)findFrequency(packet_buffer);
            //Log.d("dom: ", String.valueOf(dom));

            if(in_packet && match(dom, HANDSHAKE_END_HZ)){
                // 주파수로 바꿔서 packet 배열에 넣음
                packet.add((int) (long) Math.round((dom - START_HZ)/STEP_HZ));
                //Log.d("packet : ", String.valueOf(packet));
                List<Integer> plz = new ArrayList<>();
                plz.add(packet.poll());
                List<Integer> plzz = extract_packet(plz);
                Log.d("final: ", String.valueOf(plzz));
                break;
            }
            else if(in_packet){
                packet.add(dom);
            }
            else if(match(dom, HANDSHAKE_START_HZ)) {
                in_packet = true;
            }
        }
    }

    private List<Integer> decode_bitchunks(int chunk_bits, int[] chunks) {
        List<Integer> out_byte = new ArrayList<>();
        int next_read_chunk = 0;
        int next_read_bit = 0;
        int bytes = 0;
        int bits_left = 8;

        while (next_read_chunk < chunks.length) {
            int can_fill = chunk_bits - next_read_bit;
            int to_fill = Math.min(bits_left, can_fill);
            int offset = chunk_bits - next_read_bit - to_fill;
            bytes <<= to_fill;
            int shifted = chunks[next_read_chunk] & (((1 << to_fill) - 1) << offset);
            bytes |= shifted >> offset;
            bits_left -= to_fill;
            next_read_bit += to_fill;
            if (bits_left <= 0) {
                out_byte.add(bytes);
                bytes = 0;
                bits_left = 8;
            }
            if (next_read_bit >= chunk_bits) {
                next_read_chunk += 1;
                next_read_bit -= chunk_bits;
            }
        }


        return out_byte;
    }

    public List<Integer> extract_packet(List<Integer> freqs) {
        ArrayList<Integer> freqs_new = new ArrayList<>();

        // 주파수는 2개씩 들어온다고 생각
        for(int i = 0; i < freqs.size(); i += 2){
            freqs_new.add((int)(Math.round((freqs.get(i) -START_HZ)/STEP_HZ)));
        }
        freqs_new.remove(0);
        int[] freqs_new_ = new int[freqs_new.size()];
        for(int i = 0; i < freqs_new.size();i++){
            freqs_new_[i] = freqs_new.get(i);
        }
        return decode_bitchunks(BITS, freqs_new_);
    }




    // 주파수를 구하는 함수
    private double findFrequency(double[] toTransform) {
        int len = toTransform.length;
        double[] real = new double[len];
        double[] img = new double[len];
        double realNum;
        double imgNum;
        double[] mag = new double[len];


        Complex[] complx = transform.transform(toTransform, TransformType.FORWARD); // 결과값은 복소수가 나옴, w에 해당
        Double[] freq = this.fftfreq(complx.length, 1); // fftfreq() 함수가 허수를 실수로 바꾸어줌, freqs에 해당

        for (int i = 0; i < complx.length; i++) {
            realNum = complx[i].getReal();  // 복소수의 실수부분
            imgNum = complx[i].getImaginary();  // 복소수의 허수부분
            mag[i] = Math.sqrt((realNum * realNum) + (imgNum * imgNum));    // Math.sqrt(a) : 루트 a의 근사값, A에 해당
        }
        int max_index = 0;
        double peak_freq = abs(mag[0]);
        for(int i = 0 ; i < complx.length ; i++){
            if(peak_freq < abs(mag[i])){
                max_index = i;
                peak_freq = abs(mag[i]);
            }
            // max 값 있는 위치 저장. peak_freq 찾는 반복문
            // decode.py dominant 해당
        }

        return abs(freq[max_index] * mSampleRate);  // HZ
    }

    //Java recoding 패키지가 2의 제곱수만 받아서 기존 블럭사이즈에 가장 가까운 제곱수를 찾아주는 함수
    private int findPowerSize(int blocksize){
        int n = 1;
        while(true) {
            if (blocksize <= 3 * Math.pow(2, n)) {   //2^n
                //Log.d("findpowersize: ", String.valueOf(Math.pow(2, n)));
                return (int) Math.pow(2, n + 1);
            }
            n++;
        }

    }

    // Discrete Fourier Transform 샘플 주파수를 반환
    private Double[] fftfreq(int complx_length, double b){
        double freq_cal = 1.0 / (complx_length * b);
        Double[] result_freq = new Double[complx_length];
        int[] temp = new int[complx_length];
        int half = (complx_length - 1) / 2 + 1;
        for(int i = 0 ; i < half ; i++){
            temp[i] = i;
        }

        int temp_store = -(complx_length / 2);
        for(int i = half; i < complx_length; i++){
            temp[i] = temp_store;
            temp_store--;
        }
        for(int i = 0 ; i < complx_length ; i++){
            result_freq[i] = temp[i] * freq_cal;
        }
        return result_freq;

    }

    public boolean match(double freq1, double freq2){
        return abs(freq1 - freq2) < 20;
    }






}

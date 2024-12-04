package com.choosemuse.example.libmuse;

public class LowPassFilter {
    private double alpha;
    private double[] previousValues;

    public LowPassFilter(double alpha, int size) {
        this.alpha = alpha;
        this.previousValues = new double[size];
    }

    public double[] filter(double[] input) {
        double[] output = new double[input.length];
        for (int i = 0; i < input.length; i++) {
            output[i] = alpha * input[i] + (1 - alpha) * previousValues[i];
            previousValues[i] = output[i];
        }
        return output;
    }
}

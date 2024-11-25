package com.intretech.eegcalculation;

public class XmuseEEGCalculation {
    private static XmuseEEGCalculation instance;
    public static   synchronized XmuseEEGCalculation getInstance(){
        if (instance == null){
            instance = new XmuseEEGCalculation();
        }
        return instance;
    }

    static {
        System.loadLibrary("attention_relax_calculation_muse");
    }

    public double[] getAttentionValue(double[] ttt1,double[] ttt2,double[] ttt3,double[] ttt4,int ttta){
        return attentionMethod(ttt1, ttt2, ttt3, ttt4, ttta);
    }
    public double[] getRelaxValue(double[] ttt1,double[] ttt2,double[] ttt3,double[] ttt4,int ttta){
        return relaxMethod(ttt1, ttt2, ttt3, ttt4, ttta);
    }

    private native double[] attentionMethod(double[] ttt1,double[] ttt2,double[] ttt3,double[] ttt4,int ttta);
    private native double[] relaxMethod(double[] ttt1,double[] ttt2,double[] ttt3,double[] ttt4,int ttta);
}

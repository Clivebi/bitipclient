package com.kaopuip.core;

class RC4 {
   private int[] s;
   private int i = 0;
   private int j = 0;
   public  RC4(byte[] key){
        s = new int[256];
        int  i=0,j=0;
        int[] k = new int[256];
        int tmp=0;
        for(i=0;i<256;i++) {
            s[i] = i;
            k[i]=(key[i%key.length] &0xFF);
        }
        for(i=0;i<256;i++) {
            j=(j+s[i]+k[i])%256;
            tmp=s[i];
            s[i]=s[j];//交换s[i]和s[j]
            s[j]=tmp;
        }
    }

    public void XORStream(byte[] input,int offset) {
        int i=this.i,j=this.j,t=0;
        int k=0;
        int tmp;
        for(k=offset;k<input.length;k++)
        {
            i=(i+1)%256;
            j=(j+this.s[i])%256;
            tmp=this.s[i];
            this.s[i]=this.s[j];//交换s[x]和s[y]
            this.s[j]=tmp;
            t=(this.s[i]+this.s[j])%256;
            input[k]^=this.s[t];
        }
        this.i = i;
        this.j = j;
    }
};

package io.nekohasekai.sagernet.fmt.snell;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class SnellBean extends AbstractBean {

    public Integer version;
    public String psk;
    public String userkey;
    public Boolean reuse;
    public String network;
    public String obfsMode;
    public String obfsHost;
    public String mode;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (version == null) version = 4;
        if (psk == null) psk = "";
        if (userkey == null) userkey = "";
        if (reuse == null) reuse = false;
        if (network == null) network = "";
        if (obfsMode == null) obfsMode = "";
        if (obfsHost == null) obfsHost = "";
        if (mode == null) mode = "";
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(0);
        super.serialize(output);
        output.writeInt(version);
        output.writeString(psk);
        output.writeString(userkey);
        output.writeBoolean(reuse);
        output.writeString(network);
        output.writeString(obfsMode);
        output.writeString(obfsHost);
        output.writeString(mode);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version_ = input.readInt();
        super.deserialize(input);
        version = input.readInt();
        psk = input.readString();
        userkey = input.readString();
        reuse = input.readBoolean();
        network = input.readString();
        obfsMode = input.readString();
        obfsHost = input.readString();
        mode = input.readString();
    }

    @NotNull
    @Override
    public SnellBean clone() {
        return KryoConverters.deserialize(new SnellBean(), KryoConverters.serialize(this));
    }

    public static final Creator<SnellBean> CREATOR = new CREATOR<SnellBean>() {
        @NonNull
        @Override
        public SnellBean newInstance() {
            return new SnellBean();
        }

        @Override
        public SnellBean[] newArray(int size) {
            return new SnellBean[size];
        }
    };
}

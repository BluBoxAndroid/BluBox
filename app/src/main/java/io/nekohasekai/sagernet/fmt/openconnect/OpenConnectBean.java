package io.nekohasekai.sagernet.fmt.openconnect;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class OpenConnectBean extends AbstractBean {

    public String flavor;
    public String username;
    public String password;
    public String authGroup;
    public String cookie;
    public Boolean insecure;
    public String serverName;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (flavor == null || flavor.isBlank()) flavor = "anyconnect";
        if (username == null) username = "";
        if (password == null) password = "";
        if (authGroup == null) authGroup = "";
        if (cookie == null) cookie = "";
        if (insecure == null) insecure = false;
        if (serverName == null) serverName = "";
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(0);
        super.serialize(output);
        output.writeString(flavor);
        output.writeString(username);
        output.writeString(password);
        output.writeString(authGroup);
        output.writeString(cookie);
        output.writeBoolean(insecure);
        output.writeString(serverName);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        flavor = input.readString();
        username = input.readString();
        password = input.readString();
        authGroup = input.readString();
        cookie = input.readString();
        insecure = input.readBoolean();
        serverName = input.readString();
    }

    @Override
    public boolean canTCPing() {
        return false;
    }

    @NotNull
    @Override
    public OpenConnectBean clone() {
        return KryoConverters.deserialize(new OpenConnectBean(), KryoConverters.serialize(this));
    }

    public static final Creator<OpenConnectBean> CREATOR = new CREATOR<OpenConnectBean>() {
        @NonNull
        @Override
        public OpenConnectBean newInstance() {
            return new OpenConnectBean();
        }

        @Override
        public OpenConnectBean[] newArray(int size) {
            return new OpenConnectBean[size];
        }
    };
}

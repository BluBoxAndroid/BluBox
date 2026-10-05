package io.nekohasekai.sagernet.fmt.openvpn;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class OpenVPNBean extends AbstractBean {

    public String username;
    public String password;
    public String network;
    public String cipher;
    public String dataCiphers;
    public String auth;
    public String servers;
    public String certificate;
    public String clientCertificate;
    public String clientKey;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (username == null) username = "";
        if (password == null) password = "";
        if (network == null || network.isBlank()) network = "udp";
        if (cipher == null) cipher = "";
        if (dataCiphers == null) dataCiphers = "";
        if (auth == null) auth = "";
        if (servers == null) servers = "";
        if (certificate == null) certificate = "";
        if (clientCertificate == null) clientCertificate = "";
        if (clientKey == null) clientKey = "";
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(0);
        super.serialize(output);
        output.writeString(username);
        output.writeString(password);
        output.writeString(network);
        output.writeString(cipher);
        output.writeString(dataCiphers);
        output.writeString(auth);
        output.writeString(servers);
        output.writeString(certificate);
        output.writeString(clientCertificate);
        output.writeString(clientKey);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        username = input.readString();
        password = input.readString();
        network = input.readString();
        cipher = input.readString();
        dataCiphers = input.readString();
        auth = input.readString();
        servers = input.readString();
        certificate = input.readString();
        clientCertificate = input.readString();
        clientKey = input.readString();
    }

    @Override
    public boolean canTCPing() {
        return false;
    }

    @NotNull
    @Override
    public OpenVPNBean clone() {
        return KryoConverters.deserialize(new OpenVPNBean(), KryoConverters.serialize(this));
    }

    public static final Creator<OpenVPNBean> CREATOR = new CREATOR<OpenVPNBean>() {
        @NonNull
        @Override
        public OpenVPNBean newInstance() {
            return new OpenVPNBean();
        }

        @Override
        public OpenVPNBean[] newArray(int size) {
            return new OpenVPNBean[size];
        }
    };
}

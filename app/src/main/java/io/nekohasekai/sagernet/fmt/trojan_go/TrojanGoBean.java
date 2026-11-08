package io.nekohasekai.sagernet.fmt.trojan_go;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;
import moe.matsuri.nb4a.utils.JavaUtil;

public class TrojanGoBean extends AbstractBean {

    /**
     * The Trojan password.
     * Required, must not be an empty string, and should not contain non-ASCII printable characters.
     * Must be encoded with encodeURIComponent.
     */
    public String password;

    /**
     * Custom TLS SNI.
     * Defaults to the trojan-host value when omitted. Must not be an empty string.
     * <p>
     * Must be encoded with encodeURIComponent.
     */
    public String sni;

    /**
     * Transport type.
     * Defaults to original when omitted, but must not be an empty string.
     * Currently only original and ws are available; values such as h2 and h2+ws may be added in the future.
     * <p>
     * With original, the raw Trojan transport is used, which cannot easily pass through a CDN.
     * With ws, wss is used as the transport layer.
     */
    public String type;

    /**
     * Custom HTTP Host header.
     * Optional; defaults to the trojan-host value when omitted.
     * May be an empty string, but that can lead to unexpected behavior.
     * <p>
     * Warning: with a non-standard port (not 80 / 443), the RFC requires the port to be appended to the host name in Host, e.g. example.com:44333. Whether to comply is up to you.
     * <p>
     * Must be encoded with encodeURIComponent.
     */
    public String host;

    /**
     * Effective when the transport type is ws, h2, or h2+ws.
     * Required, must not be empty.
     * Must start with /.
     * Characters such as & # ? from the URL may be used, but it must be a valid URL path.
     * <p>
     * Must be encoded with encodeURIComponent.
     */
    public String path;

    /**
     * The encryption layer that keeps Trojan traffic cryptographically secure.
     * Optional; defaults to none, i.e. no encryption.
     * Must not be an empty string.
     * <p>
     * Must be encoded with encodeURIComponent.
     * <p>
     * When Shadowsocks algorithms are used for traffic encryption, the format is:
     * <p>
     * ss;method:password
     * <p>
     * where ss is a fixed literal and method is the encryption method, which must be one of the following:
     * <p>
     * aes-128-gcm
     * aes-256-gcm
     * chacha20-ietf-poly1305
     */
    public String encryption;

    /**
     * Extra plugin options. This field is reserved.
     * Optional, but must not be an empty string.
     */
    // not used in NB4A
    public String plugin;

    // ---

    public Boolean allowInsecure;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();

        if (password == null) password = "";
        if (sni == null) sni = "";
        if (JavaUtil.isNullOrBlank(type)) type = "original";
        if (host == null) host = "";
        if (path == null) path = "";
        if (JavaUtil.isNullOrBlank(encryption)) encryption = "none";
        if (plugin == null) plugin = "";
        if (allowInsecure == null) allowInsecure = false;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(1);
        super.serialize(output);
        output.writeString(password);
        output.writeString(sni);
        output.writeString(type);
        //noinspection SwitchStatementWithTooFewBranches
        switch (type) {
            case "ws": {
                output.writeString(host);
                output.writeString(path);
                break;
            }
        }
        output.writeString(encryption);
        output.writeString(plugin);
        output.writeBoolean(allowInsecure);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);

        password = input.readString();
        sni = input.readString();
        type = input.readString();
        //noinspection SwitchStatementWithTooFewBranches
        switch (type) {
            case "ws": {
                host = input.readString();
                path = input.readString();
                break;
            }
        }
        encryption = input.readString();
        plugin = input.readString();
        if (version >= 1) {
            allowInsecure = input.readBoolean();
        }
    }

    @NotNull
    @Override
    public TrojanGoBean clone() {
        return KryoConverters.deserialize(new TrojanGoBean(), KryoConverters.serialize(this));
    }

    public static final Creator<TrojanGoBean> CREATOR = new CREATOR<TrojanGoBean>() {
        @NonNull
        @Override
        public TrojanGoBean newInstance() {
            return new TrojanGoBean();
        }

        @Override
        public TrojanGoBean[] newArray(int size) {
            return new TrojanGoBean[size];
        }
    };
}

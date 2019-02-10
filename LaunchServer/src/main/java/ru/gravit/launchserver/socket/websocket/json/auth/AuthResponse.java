package ru.gravit.launchserver.socket.websocket.json.auth;

import io.netty.channel.ChannelHandlerContext;
import ru.gravit.launcher.OshiHWID;
import ru.gravit.launcher.events.request.AuthRequestEvent;
import ru.gravit.launcher.events.request.ErrorRequestEvent;
import ru.gravit.launcher.profiles.ClientProfile;
import ru.gravit.launchserver.LaunchServer;
import ru.gravit.launchserver.auth.AuthException;
import ru.gravit.launchserver.auth.hwid.HWIDException;
import ru.gravit.launchserver.auth.provider.AuthProvider;
import ru.gravit.launchserver.auth.provider.AuthProviderResult;
import ru.gravit.launchserver.response.profile.ProfileByUUIDResponse;
import ru.gravit.launchserver.socket.Client;
import ru.gravit.launchserver.socket.websocket.WebSocketService;
import ru.gravit.launchserver.socket.websocket.json.JsonResponseInterface;
import ru.gravit.utils.helper.IOHelper;
import ru.gravit.utils.helper.SecurityHelper;
import ru.gravit.utils.helper.VerifyHelper;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import java.util.Collection;
import java.util.UUID;

public class AuthResponse implements JsonResponseInterface {
    public String login;
    public String client;
    public String customText;

    public String password;
    public byte[] encryptedPassword;

    public AuthResponse(String login, String password, int authid, OshiHWID hwid) {
        this.login = login;
        this.password = password;
        this.authid = authid;
        this.hwid = hwid;
    }

    public int authid;
    public ConnectTypes authType;
    public OshiHWID hwid;
    public enum ConnectTypes
    {
        SERVER,CLIENT,BOT
    }

    @Override
    public String getType() {
        return "auth";
    }

    @Override
    public void execute(WebSocketService service, ChannelHandlerContext ctx, Client clientData) throws Exception {
        try {
            AuthRequestEvent result = new AuthRequestEvent();
            String ip = IOHelper.getIP(ctx.channel().remoteAddress());
            if (LaunchServer.server.limiter.isLimit(ip)) {
                AuthProvider.authError(LaunchServer.server.config.authRejectString);
                return;
            }
            if (authType != ConnectTypes.CLIENT &&!clientData.checkSign) {
                AuthProvider.authError("Don't skip Launcher Update");
                return;
            }
            if(password == null)
            {
                try {
                    password = IOHelper.decode(SecurityHelper.newRSADecryptCipher(LaunchServer.server.privateKey).
                            doFinal(encryptedPassword));
                } catch (IllegalBlockSizeException | BadPaddingException ignored) {
                    throw new AuthException("Password decryption error");
                }
            }
            clientData.permissions = LaunchServer.server.config.permissionsHandler.getPermissions(login);
            if(authType == ConnectTypes.BOT && !clientData.permissions.canBot)
            {
                AuthProvider.authError("authType: BOT not allowed for this account");
            }
            if(authType == ConnectTypes.SERVER && !clientData.permissions.canServer)
            {
                AuthProvider.authError("authType: SERVER not allowed for this account");
            }
            ru.gravit.launchserver.response.auth.AuthResponse.AuthContext context = new ru.gravit.launchserver.response.auth.AuthResponse.AuthContext(0, login, password.length(),customText, client, null, false);
            AuthProvider provider = LaunchServer.server.config.authProvider[authid];
            LaunchServer.server.authHookManager.preHook(context, clientData);
            provider.preAuth(login,password,customText,ip);
            AuthProviderResult aresult = provider.auth(login, password, ip);
            if (!VerifyHelper.isValidUsername(aresult.username)) {
                AuthProvider.authError(String.format("Illegal result: '%s'", aresult.username));
                return;
            }
            Collection<ClientProfile> profiles = LaunchServer.server.getProfiles();
            for (ClientProfile p : profiles) {
                if (p.getTitle().equals(client)) {
                    if (!p.isWhitelistContains(login)) {
                        throw new AuthException(LaunchServer.server.config.whitelistRejectString);
                    }
                    clientData.profile = p;
                }
            }
            //if (clientData.profile == null) {
            //    throw new AuthException("You profile not found");
            //}
            UUID uuid = LaunchServer.server.config.authHandler.auth(aresult);
            if(authType == ConnectTypes.CLIENT)
                 LaunchServer.server.config.hwidHandler.check(hwid, aresult.username);
            LaunchServer.server.authHookManager.postHook(context, clientData);
            clientData.isAuth = true;
            clientData.permissions = aresult.permissions;
            result.accessToken = aresult.accessToken;
            result.permissions = clientData.permissions;
            result.playerProfile = ProfileByUUIDResponse.getProfile(LaunchServer.server,uuid,aresult.username,client);
            service.sendObject(ctx, result);
        } catch (AuthException | HWIDException e) {
            service.sendObject(ctx, new ErrorRequestEvent(e.getMessage()));
        }
    }

}

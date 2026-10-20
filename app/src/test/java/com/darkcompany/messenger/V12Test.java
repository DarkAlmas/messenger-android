package com.darkcompany.messenger;

import static org.junit.Assert.*;

import android.net.Uri;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 35)
public class V12Test {
    @Test public void passwordCheck() {
        assertNotNull(NewPasswordActivity.check("", ""));
        assertEquals("Пароль должен быть не короче 10 символов", NewPasswordActivity.check("short", "short"));
        assertEquals("Пароли не совпадают", NewPasswordActivity.check("abcdefghij1", "abcdefghij2"));
        assertNull(NewPasswordActivity.check("abcdefghij1", "abcdefghij1"));
    }
    @Test public void recoveryLinks() {
        Uri f = Uri.parse("com.darkcompany.messenger.nativeapp://auth-callback#access_token=a&refresh_token=r&type=recovery");
        assertTrue(AuthCallbackActivity.isRecovery(f));
        assertEquals("a", AuthCallbackActivity.param(f, "access_token"));
        assertTrue(AuthCallbackActivity.isRecovery(Uri.parse("com.darkcompany.messenger.nativeapp://auth-callback?code=x&type=recovery")));
        assertFalse(AuthCallbackActivity.isRecovery(Uri.parse("com.darkcompany.messenger.nativeapp://auth-callback?code=x")));
    }
    @Test public void verifierKind() {
        android.content.Context c = org.robolectric.RuntimeEnvironment.getApplication();
        AuthCallbackActivity.saveVerifier(c, "v1", "recovery");
        assertEquals("recovery", AuthCallbackActivity.kind(c));
        assertEquals("v1", AuthCallbackActivity.takeVerifier(c));
        AuthCallbackActivity.saveVerifier(c, "v2");
        assertEquals("google", AuthCallbackActivity.kind(c));
    }
}

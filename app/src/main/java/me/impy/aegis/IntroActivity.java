package me.impy.aegis;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.v4.app.Fragment;

import com.github.paolorotolo.appintro.AppIntro;
import com.github.paolorotolo.appintro.AppIntroFragment;
import com.github.paolorotolo.appintro.model.SliderPage;

import javax.crypto.Cipher;

import me.impy.aegis.crypto.CryptResult;
import me.impy.aegis.crypto.MasterKey;
import me.impy.aegis.crypto.slots.FingerprintSlot;
import me.impy.aegis.crypto.slots.PasswordSlot;
import me.impy.aegis.crypto.slots.SlotCollection;
import me.impy.aegis.db.Database;
import me.impy.aegis.db.DatabaseFile;

public class IntroActivity extends AppIntro {
    public static final int RESULT_OK = 0;
    public static final int RESULT_EXCEPTION = 1;

    private CustomAuthenticatedSlide authenticatedSlide;
    private CustomAuthenticationSlide authenticationSlide;
    private Fragment endSlide;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showSkipButton(false);
        //showPagerIndicator(false);
        setGoBackLock(true);

        SliderPage homeSliderPage = new SliderPage();
        homeSliderPage.setTitle("Welcome");
        homeSliderPage.setDescription("Aegis is a secure, free and open source 2FA app");
        homeSliderPage.setImageDrawable(R.drawable.intro_shield);
        homeSliderPage.setBgColor(getResources().getColor(R.color.colorPrimary));
        addSlide(AppIntroFragment.newInstance(homeSliderPage));

        SliderPage permSliderPage = new SliderPage();
        permSliderPage.setTitle("Permissions");
        permSliderPage.setDescription("Aegis needs permission to use your camera in order to scan QR codes.");
        permSliderPage.setImageDrawable(R.drawable.intro_scanner);
        permSliderPage.setBgColor(getResources().getColor(R.color.colorAccent));
        addSlide(AppIntroFragment.newInstance(permSliderPage));
        askForPermissions(new String[]{Manifest.permission.CAMERA}, 2);

        authenticationSlide = new CustomAuthenticationSlide();
        authenticationSlide.setBgColor(getResources().getColor(R.color.colorHeaderSuccess));
        addSlide(authenticationSlide);
        authenticatedSlide = new CustomAuthenticatedSlide();
        authenticatedSlide.setBgColor(getResources().getColor(R.color.colorPrimary));
        addSlide(authenticatedSlide);

        SliderPage endSliderPage = new SliderPage();
        endSliderPage.setTitle("All done!");
        endSliderPage.setDescription("Aegis has been set up and is ready to go.");
        endSliderPage.setImageDrawable(R.drawable.intro_shield);
        endSliderPage.setBgColor(getResources().getColor(R.color.colorPrimary));
        endSlide = AppIntroFragment.newInstance(endSliderPage);
        addSlide(endSlide);
    }

    private void setException(Exception e) {
        Intent result = new Intent();
        result.putExtra("exception", e);
        setResult(RESULT_EXCEPTION, result);
        finish();
    }

    @Override
    public void onSlideChanged(Fragment oldFragment, Fragment newFragment) {
        // skip to the last slide if no encryption will be used
        if (oldFragment == authenticationSlide && newFragment != endSlide) {
            Intent intent = getIntent();
            int cryptType = intent.getIntExtra("cryptType", CustomAuthenticationSlide.CRYPT_TYPE_INVALID);
            if (cryptType == CustomAuthenticationSlide.CRYPT_TYPE_NONE) {
                // TODO: no magic indices
                getPager().setCurrentItem(5);
            }
        }
    }

    @Override
    public void onDonePressed(Fragment currentFragment) {
        super.onDonePressed(currentFragment);

        // create the database and database file
        Database database = new Database();
        DatabaseFile databaseFile = new DatabaseFile();

        int cryptType = authenticatedSlide.getCryptType();

        // generate the master key
        MasterKey masterKey = null;
        if (cryptType != CustomAuthenticationSlide.CRYPT_TYPE_NONE) {
            try {
                masterKey = MasterKey.generate();
            } catch (Exception e) {
                setException(e);
                return;
            }
        }

        SlotCollection slots = databaseFile.getSlots();
        slots.setMasterHash(masterKey.getHash());

        if (cryptType != CustomAuthenticationSlide.CRYPT_TYPE_NONE) {
            try {
                // encrypt the master key with a key derived from the user's password
                // and add it to the list of slots
                PasswordSlot slot = new PasswordSlot();
                Cipher cipher = authenticatedSlide.getCipher(slot);
                masterKey.encryptSlot(slot, cipher);
                slots.add(slot);
            } catch (Exception e) {
                setException(e);
                return;
            }
        }

        if (cryptType == CustomAuthenticationSlide.CRYPT_TYPE_FINGER) {
            try {
                // encrypt the master key with the fingerprint key
                // and add it to the list of slots
                FingerprintSlot slot = new FingerprintSlot();
                Cipher cipher = authenticatedSlide.getCipher(slot);
                masterKey.encryptSlot(slot, cipher);
                slots.add(slot);
            } catch (Exception e) {
                setException(e);
                return;
            }
        }

        // finally, save the database
        try {
            byte[] bytes = database.serialize();
            if (cryptType == CustomAuthenticationSlide.CRYPT_TYPE_NONE) {
                databaseFile.setContent(bytes);
            } else {
                CryptResult result = masterKey.encrypt(bytes);
                databaseFile.setContent(result.Data);
                databaseFile.setCryptParameters(result.Parameters);
            }
            databaseFile.save(getApplicationContext());
        } catch (Exception e) {
            setException(e);
            return;
        }

        // send the master key back to the main activity
        Intent result = new Intent();
        result.putExtra("key", masterKey);
        setResult(RESULT_OK, result);

        // skip the intro from now on
        // TODO: show the intro if we can't find any database files
        SharedPreferences prefs = this.getSharedPreferences("me.impy.aegis", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("passedIntro", true).apply();
        finish();
    }
}
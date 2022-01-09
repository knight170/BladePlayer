package v.blade.sources.spotify;

import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spotify.sdk.android.auth.AuthorizationClient;
import com.spotify.sdk.android.auth.AuthorizationRequest;
import com.spotify.sdk.android.auth.AuthorizationResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import kotlin.random.Random;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import v.blade.BladeApplication;
import v.blade.R;
import v.blade.databinding.SettingsFragmentSpotifyBinding;
import v.blade.library.Library;
import v.blade.library.Song;
import v.blade.sources.Source;

/*
 * Spotify strategy :
 * - For access to library, we do Web API access using official AUTH lib + Retrofit
 * - For the player, we use librespot-java (the player part)
 * It would be nice to use librespot for everything, but i don't think it is possible to
 * use it 'as is' for web api access...
 */
public class Spotify extends Source
{
    public static final int NAME_RESOURCE = R.string.spotify;
    public static final int DESCRIPTION_RESOURCE = R.string.spotify_desc;
    public static final int IMAGE_RESOURCE = R.drawable.ic_spotify;
    private static final int CACHE_VERSION = 1;

    //Spotify AUTH : We are using 'Authorization Code Flow' with 'PKCE extension'
    private static final String BASE_API_URL = "https://api.spotify.com/v1/";
    private static final String AUTH_TYPE = "Bearer ";
    private static final String CLIENT_ID = "048adc76814146e7bb049d89813bd6e0";
    protected static final String[] SCOPES = {"app-remote-control", "streaming", "playlist-modify-public", "playlist-modify-private", "playlist-read-private", "playlist-read-collaborative", "user-follow-modify", "user-follow-read", "user-library-modify", "user-library-read", "user-read-email", "user-read-private",
            "user-read-recently-played", "user-top-read", "user-read-playback-position", "user-read-playback-state", "user-modify-playback-state", "user-read-currently-playing"};
    private static final int SPOTIFY_REQUEST_CODE = 0x11;
    protected static final String REDIRECT_URI = "spotify-sdk://auth";

    public Spotify()
    {
        super();
        this.name = BladeApplication.appContext.getString(NAME_RESOURCE);
        this.player = new SpotifyPlayer(this);
    }

    @SuppressWarnings({"FieldMayBeFinal", "unused"})
    private static class SpotifyTokenResponse
    {
        private String access_token = "";
        private String token_type = "";
        private int expires_in = -1;
        private String refresh_token = "";
        private String scope = "";

        public SpotifyTokenResponse()
        {
        }
    }

    //Spotify login information
    //Player login
    private String account_name; //i.e. username, retrieved by api
    private String account_login; //what the user uses to login (mail or username)
    private String account_password;

    //API login
    private String ACCESS_TOKEN;
    private String REFRESH_TOKEN;
    private int TOKEN_EXPIRES_IN;
    private String AUTH_STRING;

    private Retrofit retrofit;
    private SpotifyService service;

    @Override
    public int getImageResource()
    {
        return IMAGE_RESOURCE;
    }

    @Override
    public void initSource()
    {
        if(status != SourceStatus.STATUS_NEED_INIT) return;

        status = SourceStatus.STATUS_CONNECTING;

        //build retrofit client
        retrofit = new Retrofit.Builder().baseUrl(BASE_API_URL).addConverterFactory(GsonConverterFactory.create()).build();
        service = retrofit.create(SpotifyService.class);

        //refresh access token
        OkHttpClient client = new OkHttpClient();
        RequestBody requestBody = new FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", REFRESH_TOKEN)
                .add("client_id", CLIENT_ID)
                .build();
        Request request = new Request.Builder().url("https://accounts.spotify.com/api/token")
                .post(requestBody).build();
        okhttp3.Call call = client.newCall(request);
        BladeApplication.obtainExecutorService().execute(() ->
        {
            //Prepare a looper so that we can toast
            Looper.prepare();

            //First init the player
            boolean login = ((SpotifyPlayer) player).login(account_login, account_password);
            if(!login)
            {
                Toast.makeText(BladeApplication.appContext, BladeApplication.appContext.getString(R.string.init_error) + " " + BladeApplication.appContext.getString(NAME_RESOURCE) + " (Could not login)", Toast.LENGTH_SHORT).show();
                status = SourceStatus.STATUS_NEED_INIT;
                return;
            }
            player.init();

            //Then init
            try
            {
                okhttp3.Response response = call.execute();
                if(!response.isSuccessful() || response.code() != 200 || response.body() == null)
                {
                    //noinspection ConstantConditions
                    String responseBody = response.body() == null ? "Unknown error" : response.body().string();
                    Toast.makeText(BladeApplication.appContext, BladeApplication.appContext.getString(R.string.init_error) + " " + BladeApplication.appContext.getString(NAME_RESOURCE) + " (" + response.code() + " : " + responseBody + ")", Toast.LENGTH_SHORT).show();
                    System.err.println(BladeApplication.appContext.getString(R.string.init_error) + " " + BladeApplication.appContext.getString(NAME_RESOURCE) + " (" + response.code() + " : " + responseBody + ")");
                    status = SourceStatus.STATUS_NEED_INIT;
                    return;
                }

                Gson gson = new Gson();
                //noinspection ConstantConditions
                String rstring = response.body().string();
                SpotifyTokenResponse sr = gson.fromJson(rstring, SpotifyTokenResponse.class);
                if(sr == null)
                {
                    Toast.makeText(BladeApplication.appContext, BladeApplication.appContext.getString(R.string.init_error) + " " + BladeApplication.appContext.getString(NAME_RESOURCE) + " (Could not parse JSON Token)", Toast.LENGTH_SHORT).show();
                    status = SourceStatus.STATUS_NEED_INIT;
                    return;
                }

                ACCESS_TOKEN = sr.access_token;
                TOKEN_EXPIRES_IN = sr.expires_in;

                REFRESH_TOKEN = sr.refresh_token;

                AUTH_STRING = AUTH_TYPE + ACCESS_TOKEN;

                status = SourceStatus.STATUS_READY;

                Source.saveSources();
            }
            catch(IOException e)
            {
                status = SourceStatus.STATUS_NEED_INIT;
                Toast.makeText(BladeApplication.appContext, BladeApplication.appContext.getString(R.string.init_error) + " " + BladeApplication.appContext.getString(NAME_RESOURCE) + " (IOException trying to obtain token)", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void synchronizeLibrary()
    {
        try
        {
            /* Obtain user tracks */
            int tracksLeft;
            int tracksIndex = 0;
            do
            {
                Call<SpotifyService.PagingObject<SpotifyService.SavedTrackObject>> userTracks =
                        service.getUserSavedTracks(AUTH_STRING, 50, tracksIndex * 50);
                Response<SpotifyService.PagingObject<SpotifyService.SavedTrackObject>> response =
                        userTracks.execute();

                if(response.code() != 200 || response.body() == null) break;
                SpotifyService.PagingObject<SpotifyService.SavedTrackObject> trackPaging = response.body();

                for(SpotifyService.SavedTrackObject savedTrack : trackPaging.items)
                {
                    SpotifyService.TrackObject track = savedTrack.track;
                    if(track.album == null || track.artists == null || track.album.images.length == 0)
                        continue; //TODO check ?

                    //album artists
                    String[] aartists = new String[track.album.artists.length];
                    String[] aartistsImages = new String[track.album.artists.length];
                    for(int j = 0; j < track.album.artists.length; j++)
                    {
                        aartists[j] = track.album.artists[j].name;
                    }

                    //song artists
                    String[] artists = new String[track.artists.length];
                    String[] artistsImages = new String[track.artists.length];
                    for(int j = 0; j < track.artists.length; j++)
                    {
                        artists[j] = track.artists[j].name;
                        //artistsImages[j] = track.artists[j].images //TODO artists images ?
                    }

                    Library.addSong(track.name, track.album.name, artists, this, track.id, aartists,
                            track.album.images[track.album.images.length - 2].url, track.track_number,
                            artistsImages, aartistsImages, track.album.images[0].url);
                }

                tracksLeft = trackPaging.total - 50 * (tracksIndex + 1);
                tracksIndex++;
            }
            while(tracksLeft > 0);

            /* Obtain user albums */
            int albumsLeft;
            int albumIndex = 0;
            do
            {
                Call<SpotifyService.PagingObject<SpotifyService.SavedAlbumObject>> userAlbums =
                        service.getUserSavedAlbums(AUTH_STRING, 50, albumIndex * 50);
                Response<SpotifyService.PagingObject<SpotifyService.SavedAlbumObject>> response =
                        userAlbums.execute();

                if(response.code() != 200 || response.body() == null) break;
                SpotifyService.PagingObject<SpotifyService.SavedAlbumObject> albumPaging = response.body();

                for(SpotifyService.SavedAlbumObject savedAlbum : albumPaging.items)
                {
                    SpotifyService.AlbumObject album = savedAlbum.album;
                    if(album.artists == null || album.tracks == null || album.images.length == 0)
                        continue;

                    //album artists
                    String[] aartists = new String[album.artists.length];
                    String[] aartistsImages = new String[album.artists.length];
                    for(int j = 0; j < album.artists.length; j++)
                    {
                        aartists[j] = album.artists[j].name;
                    }

                    //add every song in album
                    for(SpotifyService.SimplifiedTrackObject track : album.tracks.items)
                    {
                        //song artists
                        String[] artists = new String[track.artists.length];
                        String[] artistsImages = new String[track.artists.length];
                        for(int j = 0; j < track.artists.length; j++)
                        {
                            artists[j] = track.artists[j].name;
                            //artistsImages[j] = track.artists[j].images //TODO artists images ?
                        }

                        Library.addSong(track.name, album.name, artists, this, track.id, aartists,
                                album.images[album.images.length - 2].url, track.track_number,
                                artistsImages, aartistsImages, album.images[0].url);
                    }
                }

                albumsLeft = albumPaging.total - 50 * (albumIndex + 1);
                albumIndex++;
            }
            while(albumsLeft > 0);

            /* Obtain user playlists */
            int playlistsLeft;
            int playlistIndex = 0;
            do
            {
                Call<SpotifyService.PagingObject<SpotifyService.SimplifiedPlaylistObject>> userPlaylists =
                        service.getListOfCurrentUserPlaylists(AUTH_STRING, 50, playlistIndex * 50);
                Response<SpotifyService.PagingObject<SpotifyService.SimplifiedPlaylistObject>> response =
                        userPlaylists.execute();

                if(response.code() != 200 || response.body() == null) break;
                SpotifyService.PagingObject<SpotifyService.SimplifiedPlaylistObject> playlistPaging = response.body();

                for(SpotifyService.SimplifiedPlaylistObject playlist : playlistPaging.items)
                {
                    //Obtain song list
                    ArrayList<Song> songList = new ArrayList<>();

                    int songsLeft;
                    int songIndex = 0;
                    do
                    {
                        Call<SpotifyService.PagingObject<SpotifyService.PlaylistTrackObject>> playlistTracks =
                                service.getPlaylistItems(AUTH_STRING, playlist.id, 100, songIndex * 100);
                        Response<SpotifyService.PagingObject<SpotifyService.PlaylistTrackObject>> response2 =
                                playlistTracks.execute();

                        if(response2.code() != 200 || response2.body() == null) break;
                        SpotifyService.PagingObject<SpotifyService.PlaylistTrackObject> songsPaging = response2.body();

                        for(SpotifyService.PlaylistTrackObject playlistTrack : songsPaging.items)
                        {
                            SpotifyService.TrackObject track = playlistTrack.track;
                            if(track.album == null || track.artists == null || track.album.images.length == 0)
                                continue;

                            //album artists
                            String[] aartists = new String[track.album.artists.length];
                            String[] aartistsImages = new String[track.album.artists.length];
                            for(int j = 0; j < track.album.artists.length; j++)
                            {
                                aartists[j] = track.album.artists[j].name;
                            }

                            //song artists
                            String[] artists = new String[track.artists.length];
                            String[] artistsImages = new String[track.artists.length];
                            for(int j = 0; j < track.artists.length; j++)
                            {
                                artists[j] = track.artists[j].name;
                                //artistsImages[j] = track.artists[j].images //TODO artists images ?
                            }

                            Song song = Library.addSongHandle(track.name, track.album.name, artists, this, track.id, aartists,
                                    track.album.images[track.album.images.length - 2].url, track.track_number,
                                    artistsImages, aartistsImages, track.album.images[0].url);
                            songList.add(song);
                        }

                        songsLeft = songsPaging.total - 100 * (songIndex + 1);
                        songIndex++;
                    }
                    while(songsLeft > 0);

                    Library.addPlaylist(playlist.name, songList, playlist.images.length == 0 ? null : (playlist.images[0] == null ? null : playlist.images[0].url));
                }

                playlistsLeft = playlistPaging.total - 50 * (playlistIndex + 1);
                playlistIndex++;
            }
            while(playlistsLeft > 0);
        }
        catch(IOException e)
        {
            e.printStackTrace();
        }
    }

    @Override
    public Fragment getSettingsFragment()
    {
        return new Spotify.SettingsFragment(this);
    }

    @Override
    public JsonObject saveToJSON()
    {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("class", Spotify.class.getName());
        jsonObject.addProperty("account_name", account_name);
        jsonObject.addProperty("refresh_token", REFRESH_TOKEN);
        jsonObject.addProperty("cache_version", CACHE_VERSION);
        jsonObject.addProperty("account_login", account_login);
        jsonObject.addProperty("account_password", account_password);

        return jsonObject;
    }

    @Override
    public void restoreFromJSON(JsonObject jsonObject)
    {
        int cache_version = jsonObject.get("cache_version") == null ? 0 : jsonObject.get("cache_version").getAsInt();
        if(cache_version > CACHE_VERSION)
            System.err.println("Spotify cached source with version greater than current... Trying to read with old way.");

        JsonElement accountLoginJson = jsonObject.get("account_login");
        if(accountLoginJson != null) account_login = accountLoginJson.getAsString();
        else account_login = "null";

        JsonElement accountPasswordJson = jsonObject.get("account_password");
        if(accountPasswordJson != null) account_password = accountPasswordJson.getAsString();
        else account_password = "null";

        JsonElement accountNameJson = jsonObject.get("account_name");
        if(accountNameJson != null) account_name = accountNameJson.getAsString();
        else account_name = account_login;

        JsonElement refreshTokenJson = jsonObject.get("refresh_token");
        if(refreshTokenJson != null) REFRESH_TOKEN = refreshTokenJson.getAsString();
        else status = SourceStatus.STATUS_DOWN;
    }

    public static class SettingsFragment extends Fragment
    {
        private final Spotify spotify;
        private SettingsFragmentSpotifyBinding binding;

        private String codeVerifier;

        private SettingsFragment(Spotify spotify)
        {
            super(R.layout.settings_fragment_spotify);
            this.spotify = spotify;
        }

        public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
        {
            binding = SettingsFragmentSpotifyBinding.inflate(inflater, container, false);

            //Set status text
            switch(spotify.getStatus())
            {
                case STATUS_DOWN:
                    binding.settingsSpotifyStatus.setText(R.string.source_down_desc);
                    break;
                case STATUS_NEED_INIT:
                    binding.settingsSpotifyStatus.setText(R.string.source_need_init_desc);
                    break;
                case STATUS_CONNECTING:
                    binding.settingsSpotifyStatus.setText(R.string.source_connecting_desc);
                    break;
                case STATUS_READY:
                    binding.settingsSpotifyStatus.setText(R.string.source_ready_desc);
                    break;
            }

            //Set account text
            if(spotify.account_name == null)
            {
                binding.settingsSpotifyAccount.setText(R.string.disconnected);
                binding.settingsSpotifyAccount.setTextColor(getResources().getColor(R.color.errorRed));
            }
            else
            {
                binding.settingsSpotifyAccount.setText(spotify.account_name);
                binding.settingsSpotifyAccount.setTextColor(getResources().getColor(R.color.okGreen));
            }

            //Set 'sign in' button action : call spotify auth
            binding.settingsSpotifySignIn.setOnClickListener(v ->
            {
                //Try to login player
                String userName = binding.settingsSpotifyUser.getText().toString();
                String userPass = binding.settingsSpotifyPassword.getText().toString();

                Future<Boolean> future = BladeApplication.obtainExecutorService().submit(() ->
                        ((SpotifyPlayer) spotify.getPlayer()).login(userName, userPass));

                boolean login;
                try
                {
                    login = future.get();
                }
                catch(ExecutionException | InterruptedException e)
                {
                    Toast.makeText(getContext(), getString(R.string.auth_error) + " (Could not log in, interrupted, maybe try again)", Toast.LENGTH_SHORT).show();
                    e.printStackTrace();
                    return;
                }

                if(!login)
                {
                    Toast.makeText(getContext(), getString(R.string.auth_error) + " (Could not log in)", Toast.LENGTH_SHORT).show();
                    return;
                }

                spotify.getPlayer().init();
                spotify.account_login = userName;
                spotify.account_password = userPass;

                //Generate random code for PKCE
                int codeLen = Random.Default.nextInt(43, 128);

                byte leftLimit = 97; // letter 'a'
                byte rightLimit = 122; // letter 'z'
                byte[] randomCode = new byte[codeLen];
                for(int i = 0; i < codeLen; i++)
                    randomCode[i] = (byte) Random.Default.nextInt(leftLimit, rightLimit + 1);

                codeVerifier = new String(randomCode, StandardCharsets.US_ASCII);
                MessageDigest digest;
                try
                {
                    digest = MessageDigest.getInstance("SHA-256");
                }
                catch(NoSuchAlgorithmException e)
                {
                    Toast.makeText(getContext(), getString(R.string.auth_error) + " (Cannot calculate SHA256 Hash)", Toast.LENGTH_SHORT).show();
                    return;
                }

                digest.reset();
                byte[] code = digest.digest(randomCode);

                String base64code = Base64.encodeToString(code, Base64.URL_SAFE);
                //Remove trailing '=', '\n', ...
                int index;
                for(index = base64code.length() - 1; index >= 0; index--)
                {
                    if(base64code.charAt(index) != '='
                            && base64code.charAt(index) != '\n'
                            && base64code.charAt(index) != '\r'
                            && base64code.charAt(index) != ' ') break;
                }
                base64code = base64code.substring(0, index + 1);

                AuthorizationRequest request = new AuthorizationRequest.Builder(CLIENT_ID,
                        AuthorizationResponse.Type.CODE, REDIRECT_URI)
                        .setShowDialog(false).setScopes(SCOPES)
                        .setCustomParam("code_challenge_method", "S256")
                        .setCustomParam("code_challenge", base64code).build();
                AuthorizationClient.openLoginActivity(requireActivity(), SPOTIFY_REQUEST_CODE, request);
            });

            binding.settingsSpotifyInit.setOnClickListener(view ->
            {
                spotify.initSource();
                //TODO update current interface on callback
                Source.saveSources();
            });

            binding.settingsSpotifyRemove.setOnClickListener(view ->
            {
                Source.SOURCES.remove(spotify);
                requireActivity().onBackPressed();
                Toast.makeText(BladeApplication.appContext, R.string.please_sync_to_apply, Toast.LENGTH_LONG).show();
                //this is 'scheduleSave' after library sync
            });

            return binding.getRoot();
        }

        //We don't care about deprecation as this is just called from SettingsActivity ; could be changed
        @SuppressWarnings("deprecation")
        @Override
        public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data)
        {
            super.onActivityResult(requestCode, resultCode, data);

            if(requestCode != SPOTIFY_REQUEST_CODE) return;

            final AuthorizationResponse response = AuthorizationClient.getResponse(resultCode, data);
            if(response.getType() != AuthorizationResponse.Type.CODE) return;

            if(response.getError() != null && !response.getError().isEmpty())
            {
                Toast.makeText(getContext(), getString(R.string.auth_error) + " (" + response.getError() + ")", Toast.LENGTH_SHORT).show();
                return;
            }

            /* Authentication ok : we got code ; now we need to obtain access and refresh tokens */
            final String code = response.getCode();

            OkHttpClient client = new OkHttpClient();
            RequestBody body = new FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("redirect_uri", REDIRECT_URI)
                    .add("client_id", CLIENT_ID)
                    .add("code_verifier", codeVerifier)
                    .build();
            Request request = new Request.Builder().url("https://accounts.spotify.com/api/token")
                    .post(body).build();
            okhttp3.Call call = client.newCall(request);

            BladeApplication.obtainExecutorService().execute(() ->
            {
                //Prepare a looper so that we can Toast on error
                Looper.prepare();

                try
                {
                    okhttp3.Response postResponse = call.execute();
                    if(!postResponse.isSuccessful() || postResponse.code() != 200 || postResponse.body() == null)
                    {
                        //noinspection ConstantConditions
                        String responseBody = (postResponse.body() == null ? "Unknown error" : postResponse.body().string());
                        Toast.makeText(getContext(), getString(R.string.auth_error) + " (" + postResponse.code() + " : " + responseBody + ")", Toast.LENGTH_SHORT).show();
                        System.err.println("Spotify AUTH token error : " + postResponse.code() + " : " + responseBody);
                        return;
                    }

                    Gson gson = new Gson();
                    String rstring = Objects.requireNonNull(postResponse.body()).string();
                    SpotifyTokenResponse sr = gson.fromJson(rstring, SpotifyTokenResponse.class);
                    if(sr == null)
                    {
                        Toast.makeText(getContext(), getString(R.string.auth_error) + " (Could not parse JSON Token)", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    //set token
                    spotify.ACCESS_TOKEN = sr.access_token;
                    spotify.REFRESH_TOKEN = sr.refresh_token;
                    spotify.TOKEN_EXPIRES_IN = sr.expires_in;
                    spotify.AUTH_STRING = AUTH_TYPE + spotify.ACCESS_TOKEN;

                    //init and set status
                    spotify.retrofit = new Retrofit.Builder().baseUrl(BASE_API_URL).addConverterFactory(GsonConverterFactory.create()).build();
                    spotify.service = spotify.retrofit.create(SpotifyService.class);
                    spotify.status = SourceStatus.STATUS_READY;

                    //obtain account name
                    SpotifyService.UserInformationObject user = spotify.service.getUser(spotify.AUTH_STRING).execute().body();
                    String accountName = (user == null ? "null" : (user.display_name == null ? "null" : user.display_name));
                    spotify.account_name = accountName;

                    //Re-set status and account textboxes
                    requireActivity().runOnUiThread(() ->
                    {
                        binding.settingsSpotifyStatus.setText(R.string.source_ready_desc);
                        binding.settingsSpotifyAccount.setText(accountName);
                        binding.settingsSpotifyAccount.setTextColor(getResources().getColor(R.color.okGreen));
                    });

                    //Re-Save all sources
                    //this is 'scheduleSave' after library Sync
                    Toast.makeText(BladeApplication.appContext, R.string.please_sync_to_apply, Toast.LENGTH_LONG).show();
                }
                catch(IOException e)
                {
                    Toast.makeText(getContext(), getString(R.string.auth_error) + " (IOException trying to obtain tokens)", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }
}

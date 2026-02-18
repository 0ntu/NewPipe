package org.schabi.newpipe.local.dialog;

import static org.schabi.newpipe.database.playlist.model.PlaylistEntity.DEFAULT_THUMBNAIL_ID;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.schabi.newpipe.NewPipeDatabase;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.playlist.PlaylistDuplicatesEntry;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.local.LocalItemListAdapter;
import org.schabi.newpipe.local.playlist.LocalPlaylistManager;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;

public final class PlaylistAppendDialog extends PlaylistDialog {
    private static final String TAG = PlaylistAppendDialog.class.getCanonicalName();

    // all playlists from the user
    private List<PlaylistDuplicatesEntry> userPlaylists = new ArrayList<>();
    private EditText playlistSearchText;
    private RecyclerView playlistRecyclerView;
    private LocalItemListAdapter playlistAdapter;
    private TextView playlistDuplicateIndicator;

    private final CompositeDisposable playlistDisposables = new CompositeDisposable();

    /**
     * Create a new instance of {@link PlaylistAppendDialog}.
     *
     * @param streamEntities a list of {@link StreamEntity} to be added to playlists
     * @return a new instance of {@link PlaylistAppendDialog}
     */
    public static PlaylistAppendDialog newInstance(final List<StreamEntity> streamEntities) {
        final PlaylistAppendDialog dialog = new PlaylistAppendDialog();
        dialog.setStreamEntities(streamEntities);
        return dialog;
    }

    /*//////////////////////////////////////////////////////////////////////////
    // LifeCycle - Creation
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater, final ViewGroup container,
                             final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_playlists, container);
    }

    @Override
    public void onViewCreated(@NonNull final View view, @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        final LocalPlaylistManager playlistManager =
                new LocalPlaylistManager(NewPipeDatabase.getInstance(requireContext()));

        playlistAdapter = new LocalItemListAdapter(getActivity());
        playlistAdapter.setSelectedListener(selectedItem -> {
            final List<StreamEntity> entities = getStreamEntities();
            if (selectedItem instanceof PlaylistDuplicatesEntry && entities != null) {
                onPlaylistSelected(playlistManager,
                        (PlaylistDuplicatesEntry) selectedItem, entities);
            }
        });

        // Get playlist search text
        playlistSearchText = view.findViewById(R.id.playlistSearchEditText);
        playlistRecyclerView = view.findViewById(R.id.playlist_list);
        playlistRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        playlistRecyclerView.setAdapter(playlistAdapter);

        playlistDuplicateIndicator = view.findViewById(R.id.playlist_duplicate);

        if (playlistSearchText != null) {
            playlistSearchText.addTextChangedListener(new TextWatcher() {
                @Override
                public void afterTextChanged(final Editable s) {
                    final String text = s.toString();
                    if (text.isEmpty()) { // if empty, show all
                        playlistAdapter.clearStreamItemList();
                        playlistAdapter.addItems(userPlaylists);
                    } else { // if text, filter for text
                        filterUserPlaylists(text);
                    }
                }

                @Override
                public void beforeTextChanged(final CharSequence s, final int start,
                                              final int count, final int after) {
                }

                @Override
                public void onTextChanged(final CharSequence s, final int start,
                                          final int before, final int count) {
                }
            });
        }

        final View newPlaylistButton = view.findViewById(R.id.newPlaylist);
        newPlaylistButton.setOnClickListener(ignored -> openCreatePlaylistDialog());


        playlistDisposables.add(playlistManager
                .getPlaylistDuplicates(getStreamEntities().get(0).getUrl())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onPlaylistsReceived));
    }

    /*//////////////////////////////////////////////////////////////////////////
    // LifeCycle - Destruction
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        playlistDisposables.dispose();
        if (playlistAdapter != null) {
            playlistAdapter.unsetSelectedListener();
        }

        playlistDisposables.clear();
        userPlaylists.clear();
        playlistSearchText = null;
        playlistRecyclerView = null;
        playlistAdapter = null;
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Helper
    //////////////////////////////////////////////////////////////////////////*/

    /**
     * Display create playlist dialog.
     */
    public void openCreatePlaylistDialog() {
        if (getStreamEntities() == null || !isAdded()) {
            return;
        }

        final PlaylistCreationDialog playlistCreationDialog =
                PlaylistCreationDialog.newInstance(getStreamEntities());
        // Move the dismissListener to the new dialog.
        playlistCreationDialog.setOnDismissListener(this.getOnDismissListener());
        this.setOnDismissListener(null);

        playlistCreationDialog.show(getParentFragmentManager(), TAG);
        requireDialog().dismiss();
    }

    private void onPlaylistsReceived(@NonNull final List<PlaylistDuplicatesEntry> playlists) {
        if (playlistAdapter != null
                && playlistRecyclerView != null
                && playlistDuplicateIndicator != null) {
            playlistAdapter.clearStreamItemList();
            playlistAdapter.addItems(playlists);
            playlistRecyclerView.setVisibility(View.VISIBLE);
            playlistDuplicateIndicator.setVisibility(
                    anyPlaylistContainsDuplicates(playlists) ? View.VISIBLE : View.GONE);
            userPlaylists = playlists;
        }
    }

    private boolean anyPlaylistContainsDuplicates(final List<PlaylistDuplicatesEntry> playlists) {
        return playlists.stream()
                .anyMatch(playlist -> playlist.getTimesStreamIsContained() > 0);
    }

    private void onPlaylistSelected(@NonNull final LocalPlaylistManager manager,
                                    @NonNull final PlaylistDuplicatesEntry playlist,
                                    @NonNull final List<StreamEntity> streams) {

        final String toastText;
        if (playlist.getTimesStreamIsContained() > 0) {
            toastText = getString(R.string.playlist_add_stream_success_duplicate,
                    playlist.getTimesStreamIsContained());
        } else {
            toastText = getString(R.string.playlist_add_stream_success);
        }

        final Toast successToast = Toast.makeText(getContext(), toastText, Toast.LENGTH_SHORT);

        playlistDisposables.add(manager.appendToPlaylist(playlist.getUid(), streams)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(ignored -> {
                    successToast.show();

                    if (playlist.getThumbnailStreamId() != null
                            && playlist.getThumbnailStreamId() == DEFAULT_THUMBNAIL_ID
                    ) {
                        playlistDisposables.add(manager
                                .changePlaylistThumbnail(playlist.getUid(), streams.get(0).getUid(),
                                        false)
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe(ignore -> successToast.show()));
                    }
                }));

        requireDialog().dismiss();
    }

    private void filterUserPlaylists(final String text) {
        // nothing to filter for
        if (playlistAdapter == null || userPlaylists.isEmpty() || text.isEmpty()) {
            return;
        }

        // filter playlists name for text
        final List<PlaylistDuplicatesEntry> filteredPlaylists = new ArrayList<>();
        for (final PlaylistDuplicatesEntry playlist : userPlaylists) {
            if (playlist.getOrderingName() == null) {
                continue;
            }

            if (playlist.getOrderingName().contains(text)) {
                filteredPlaylists.add(playlist);
            }
        }
        playlistAdapter.clearStreamItemList();
        playlistAdapter.addItems(filteredPlaylists);
    }
}

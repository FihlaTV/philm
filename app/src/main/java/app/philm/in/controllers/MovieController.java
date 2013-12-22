package app.philm.in.controllers;

import com.google.common.base.Preconditions;

import com.jakewharton.trakt.entities.Movie;
import com.squareup.otto.Subscribe;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import app.philm.in.state.MoviesState;
import app.philm.in.trakt.Trakt;
import app.philm.in.util.BackgroundRunnable;
import app.philm.in.util.PhilmCollections;

public class MovieController extends BaseUiController<MovieController.MovieUi,
        MovieController.MovieUiCallbacks> {

    public static enum Error {
        REQUIRE_LOGIN
    }

    public static enum Filter {
        WATCHED, UNWATCHED;

        public boolean isMovieFiltered(Movie movie) {
            return isMovieFiltered(movie, this);
        }

        public List<Filter> getMutuallyExclusiveFilters() {
            switch (this) {
                case WATCHED:
                    return Arrays.asList(UNWATCHED);
                case UNWATCHED:
                    return Arrays.asList(WATCHED);
            }
            return null;
        }

        private static boolean isMovieFiltered(Movie movie, Filter filter) {
            switch (filter) {
                case WATCHED:
                    if (movie.watched != null) {
                        return movie.watched;
                    } else if (movie.plays != null) {
                        return movie.plays > 0;
                    }
                    break;
                case UNWATCHED:
                    return !isMovieFiltered(movie, WATCHED);
            }
            return false;
        }
    }

    public static enum MovieQueryType {
        LIBRARY, TRENDING;

        public boolean requireLogin() {
            switch (this) {
                case LIBRARY:
                    return true;
                default:
                    return false;
            }
        }
    }

    public interface MovieUi extends BaseUiController.Ui<MovieUiCallbacks> {
        void setItems(List<Movie> items);
        MovieQueryType getMovieQueryType();
        void showError(Error error);

        void showActiveFilters(Set<Filter> filters);
    }

    public interface MovieUiCallbacks {
        void addFilter(Filter filter);

        void removeFilter(Filter filter);

        void clearFilters();
    }

    private final MoviesState mMoviesState;
    private final ExecutorService mExecutor;
    private final Trakt mTraktClient;

    public MovieController(
            MoviesState movieState,
            Trakt traktClient,
            ExecutorService executor) {
        super();
        mMoviesState = Preconditions.checkNotNull(movieState, "moviesState cannot be null");
        mTraktClient = Preconditions.checkNotNull(traktClient, "trackClient cannot be null");
        mExecutor = Preconditions.checkNotNull(executor, "executor cannot be null");
    }

    @Override
    protected void onInited() {
        super.onInited();
        mMoviesState.registerForEvents(this);
    }

    @Override
    protected void onSuspended() {
        super.onSuspended();
        mMoviesState.unregisterForEvents(this);
    }

    @Override
    protected MovieUiCallbacks createUiCallbacks() {
        return new MovieUiCallbacks() {

            @Override
            public void addFilter(Filter filter) {
                if (mMoviesState.getFilters().add(filter)) {
                    removeMutuallyExclusiveFilters(filter);
                    populateUi();
                }
            }

            @Override
            public void removeFilter(Filter filter) {
                if (mMoviesState.getFilters().remove(filter)) {
                    populateUi();
                }
            }

            @Override
            public void clearFilters() {
                if (!mMoviesState.getFilters().isEmpty()) {
                    mMoviesState.getFilters().clear();
                    populateUi();
                }
            }
        };
    }

    @Override
    protected void onUiAttached() {
        super.onUiAttached();

        switch (getUi().getMovieQueryType()) {
            case TRENDING:
                fetchTrendingIfNeeded();
                break;
            case LIBRARY:
                fetchLibraryIfNeeded();
                break;
        }
    }

    @Override
    protected void populateUi() {
        final MovieQueryType queryType = getUi().getMovieQueryType();

        if (queryType.requireLogin() && TextUtils.isEmpty(mMoviesState.getUsername())) {
            getUi().showError(Error.REQUIRE_LOGIN);
            return;
        }

        getUi().showActiveFilters(mMoviesState.getFilters());

        switch (queryType) {
            case TRENDING:
                populateTrendingUi();
                break;
            case LIBRARY:
                populateLibraryUi();
                break;
        }
    }

    private void populateLibraryUi() {
        List<Movie> items = mMoviesState.getLibrary();
        if (requireFiltering()) {
            items = filterMovies(items);
        }
        getUi().setItems(items);
    }

    private void populateTrendingUi() {
        List<Movie> items = mMoviesState.getTrending();
        if (requireFiltering()) {
            items = filterMovies(items);
        }
        getUi().setItems(items);
    }

    private boolean requireFiltering() {
        return !PhilmCollections.isEmpty(mMoviesState.getFilters());
    }

    private List<Movie> filterMovies(List<Movie> movies) {
        Preconditions.checkNotNull(movies, "movies cannot be null");

        final Set<Filter> filters = mMoviesState.getFilters();
        Preconditions.checkNotNull(filters, "filters cannot be null");
        Preconditions.checkState(!filters.isEmpty(), "filters cannot be empty");

        ArrayList<Movie> filteredMovies = new ArrayList<Movie>();
        for (Movie movie : movies) {
            boolean filtered = true;
            for (Filter filter : filters) {
                if (!filter.isMovieFiltered(movie)) {
                    filtered = false;
                    break;
                }
            }
            if (filtered) {
                filteredMovies.add(movie);
            }
        }
        return filteredMovies;
    }

    private void fetchLibrary() {
        mExecutor.execute(new FetchLibraryRunnable(mMoviesState.getUsername()));
    }

    private void fetchLibraryIfNeeded() {
        if (PhilmCollections.isEmpty(mMoviesState.getLibrary())) {
            fetchLibrary();
        }
    }

    private void fetchTrending() {
        mExecutor.execute(new FetchTrendingRunnable());
    }

    private void fetchTrendingIfNeeded() {
        if (PhilmCollections.isEmpty(mMoviesState.getTrending())) {
            fetchTrending();
        }
    }

    private void removeMutuallyExclusiveFilters(final Filter filter) {
        List<Filter> mutuallyExclusives = filter.getMutuallyExclusiveFilters();
        if (!PhilmCollections.isEmpty(mutuallyExclusives)) {
            for (Filter mutualFilter : mutuallyExclusives) {
                mMoviesState.getFilters().remove(mutualFilter);
            }
        }
    }

    @Subscribe
    public void onLibraryChanged(MoviesState.LibraryChangedEvent event) {
        populateLibraryUi();
    }

    @Subscribe
    public void onTrendingChanged(MoviesState.TrendingChangedEvent event) {
        populateTrendingUi();
    }

    private class FetchTrendingRunnable extends BackgroundRunnable<List<Movie>> {
        @Override
        public List<Movie> runAsync() {
            return mTraktClient.moviesService().trending();
        }

        @Override
        public void postExecute(List<Movie> result) {
            mMoviesState.setTrending(result);
        }
    }

    private class FetchLibraryRunnable extends BackgroundRunnable<List<Movie>> {
        private final String mUsername;

        FetchLibraryRunnable(String username) {
            mUsername = Preconditions.checkNotNull(username, "username cannot be null");
        }

        @Override
        public List<Movie> runAsync() {
            return mTraktClient.philmUserService().libraryMoviesAll(mUsername);
        }

        @Override
        public void postExecute(List<Movie> result) {
            mMoviesState.setLibrary(result);
        }
    }
}

import { FaqEntry } from '../../shared/media';

/**
 * Home FAQ copy (spec 3.9). Plain statements of what the app does; the
 * live TV entry is shown only when live TV is configured.
 */
export const HOME_FAQ: readonly FaqEntry[] = [
  {
    question: 'What is Media Manager?',
    answer: 'A catalog and streaming server for your own collection: movies, TV, music, books, live TV, and cameras, in one place.',
  },
  {
    question: 'Where can I watch my library?',
    answer: 'Here in the browser, and in the Media Manager apps for iPhone and iPad, Android TV, and Roku.',
  },
  {
    question: 'Does it remember where I left off?',
    answer: 'Yes. Playback, reading, and listening positions are saved for each person and appear in Continue Watching, Resume Reading, and Continue Listening.',
  },
  {
    question: 'Where do the posters and artwork come from?',
    answer: 'Movie and TV artwork comes from TMDB, book covers from Open Library, and album art from the Cover Art Archive.',
  },
  {
    question: 'How do I ask for something new?',
    answer: 'Add it to My Wish List. When it joins the library it appears under Recently Added.',
  },
];

/** Appended to [HOME_FAQ] when live TV is configured. */
export const HOME_FAQ_LIVE_TV: FaqEntry = {
  question: 'Can I watch live TV?',
  answer: 'Yes. Channels from your HDHomeRun tuner are under Live TV.',
};

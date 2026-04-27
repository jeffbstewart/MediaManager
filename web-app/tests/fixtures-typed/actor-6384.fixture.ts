// Typed fixture for the gRPC GetActorDetail response. Mirrors
// fixtures/catalog/actor.json (Keanu Reeves, person 6384).
// The proto wire wraps TMDB image paths in a full
// https://image.tmdb.org/t/p/w500/… URL; the SPA-side adapter strips
// that wrapper before handing the path to tmdbImageUrl().

import { create } from '@bufbuild/protobuf';
import {
  ActorDetailSchema,
  MediaFormat,
  MediaType,
  Quality,
  type ActorDetail,
} from '../../src/app/proto-gen/common_pb';
import { Month } from '../../src/app/proto-gen/time_pb';

export const actor6384: ActorDetail = create(ActorDetailSchema, {
  name: 'Keanu Reeves',
  headshotUrl: '/headshots/6384',
  biography: 'Canadian actor and musician, best known for his roles in The Matrix and John Wick.',
  birthday: { year: 1964, month: Month.SEPTEMBER, day: 2 },
  placeOfBirth: 'Beirut, Lebanon',
  knownForDepartment: 'Acting',
  ownedTitles: [
    {
      title: {
        id: 100n,
        name: 'The Matrix',
        mediaType: MediaType.MOVIE,
        year: 1999,
        posterUrl: '/posters/w185/100',
        quality: Quality.HD,
        playable: true,
      },
      characterName: 'Neo',
    },
  ],
  otherWorks: [
    {
      tmdbId: 245891,
      title: 'John Wick',
      mediaType: MediaType.MOVIE,
      characterName: 'John Wick',
      releaseYear: 2014,
      posterUrl: 'https://image.tmdb.org/t/p/w500/fZPSd91yGE9fCcCe6OoQZ6ljZeB.jpg',
      popularity: 85.2,
      wished: false,
    },
  ],
});

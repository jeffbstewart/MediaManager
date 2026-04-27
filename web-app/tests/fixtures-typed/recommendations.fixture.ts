// Typed proto fixture for ArtistService.ListArtistRecommendations.
// Mirrors catalog/recommendations.json (the legacy REST fixture)
// but in proto shape — rgid is what flows on the wire; the SPA
// constructs the cover URL same-origin via
// /proxy/caa/release-group/{rgid}/front-250.

import { create } from '@bufbuild/protobuf';
import {
  ArtistRecommendationsResponseSchema,
  type ArtistRecommendationsResponse,
} from '../../src/app/proto-gen/artist_pb';

export const artistRecommendations: ArtistRecommendationsResponse = create(
  ArtistRecommendationsResponseSchema,
  {
    artists: [
      {
        suggestedArtistMbid: 'abc-1',
        suggestedArtistName: 'Bill Evans',
        score: 0.92,
        voters: [{ mbid: 'v-1', name: 'Miles Davis', albumCount: 4 }],
        representativeReleaseGroupId: 'rg-1',
        representativeReleaseTitle: 'Sunday at the Village Vanguard',
      },
      {
        suggestedArtistMbid: 'abc-2',
        suggestedArtistName: 'John Coltrane',
        score: 0.87,
        voters: [
          { mbid: 'v-1', name: 'Miles Davis',     albumCount: 4 },
          { mbid: 'v-2', name: 'Bill Evans',      albumCount: 2 },
          { mbid: 'v-3', name: 'Thelonious Monk', albumCount: 1 },
        ],
        representativeReleaseGroupId: 'rg-2',
        representativeReleaseTitle: 'A Love Supreme',
      },
    ],
  },
);

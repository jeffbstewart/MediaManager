// Typed fixture for the gRPC GetTitleDetail response — book branch.
// Mirrors tests/fixtures/catalog/title.book.json: Frank Herbert's
// "Dune", first volume of the Dune series, hardback only (no
// readable_editions because the legacy fixture has none either).

import { create } from '@bufbuild/protobuf';
import {
  MediaFormat,
  MediaType,
  TitleDetailSchema,
  type TitleDetail,
} from '../../src/app/proto-gen/common_pb';

export const titleBook300: TitleDetail = create(TitleDetailSchema, {
  title: {
    id: 300n,
    name: 'Dune',
    mediaType: MediaType.BOOK,
    year: 1965,
    description: 'Set on the desert planet Arrakis, follows young Paul Atreides.',
  },
  genres: [{ id: 40n, name: 'Science Fiction' }],
  displayFormats: ['Hardback'],
  adminMediaItems: [
    { mediaItemId: 7001n, mediaFormat: MediaFormat.HARDBACK },
  ],
  book: {
    authors: [{ id: 1n, name: 'Frank Herbert' }],
    bookSeries: { id: 1n, name: 'Dune', number: '1' },
    pageCount: 688,
    firstPublicationYear: 1965,
    openLibraryWorkId: 'OL893415W',
  },
});

// Re-export the proto module so consumers of MediaManagerCore get the
// generated types (Mediamanager_*, MM*) without a separate import. Apps
// and extensions only need `import MediaManagerCore`.
@_exported import MediaManagerProtos

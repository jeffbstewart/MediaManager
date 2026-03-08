sub init()
    print "[MM] FeedTask: init"
end sub

sub doTask()
    serverUrl = m.top.serverUrl
    apiKey = m.top.apiKey

    if serverUrl = "" or serverUrl = invalid or apiKey = "" or apiKey = invalid
        print "[MM] FeedTask: ERROR — server URL or API key not set"
        m.top.feedError = "Server URL and API Key are required."
        return
    end if

    feedUrl = serverUrl + "/roku/feed.json?key=" + apiKey

    ' Mask key for logging
    maskedUrl = serverUrl + "/roku/feed.json?key="
    if len(apiKey) > 8
        maskedUrl = maskedUrl + left(apiKey, 8) + "..."
    else
        maskedUrl = maskedUrl + apiKey
    end if
    print "[MM] FeedTask: fetching " ; maskedUrl

    ' Fetch feed JSON
    transfer = CreateObject("roUrlTransfer")
    transfer.SetUrl(feedUrl)
    transfer.SetCertificatesFile("common:/certs/ca-bundle.crt")
    transfer.InitClientCertificates()
    transfer.EnableEncodings(true)

    ' Set timeout (15 seconds)
    port = CreateObject("roMessagePort")
    transfer.SetMessagePort(port)

    if not transfer.AsyncGetToString()
        print "[MM] FeedTask: ERROR — failed to start HTTP request"
        m.top.feedError = "Failed to start HTTP request."
        return
    end if

    print "[MM] FeedTask: waiting for response (15s timeout)..."
    msg = wait(15000, port)
    if msg = invalid
        print "[MM] FeedTask: ERROR — request timed out"
        m.top.feedError = "Request timed out. Check server URL."
        return
    end if

    responseCode = msg.GetResponseCode()
    print "[MM] FeedTask: HTTP " ; str(responseCode).trim()

    if responseCode <> 200
        if responseCode = 401
            print "[MM] FeedTask: ERROR — auth failed (401)"
            m.top.feedError = "Authentication failed. Check API Key."
        else if responseCode < 0
            print "[MM] FeedTask: ERROR — connection failed (code=" ; str(responseCode).trim() ; ")"
            m.top.feedError = "Connection failed. Is the server running?"
        else
            print "[MM] FeedTask: ERROR — server returned " ; str(responseCode).trim()
            m.top.feedError = "Server returned error " + str(responseCode).trim()
        end if
        return
    end if

    body = msg.GetString()
    if body = "" or body = invalid
        print "[MM] FeedTask: ERROR — empty response body"
        m.top.feedError = "Empty response from server."
        return
    end if

    print "[MM] FeedTask: response body=" ; str(len(body)).trim() ; " bytes"

    ' Parse JSON
    feed = parseJSON(body)
    if feed = invalid
        print "[MM] FeedTask: ERROR — JSON parse failed"
        m.top.feedError = "Invalid JSON response from server."
        return
    end if

    ' Count items for logging
    movieCount = 0
    seriesCount = 0
    if feed.movies <> invalid then movieCount = feed.movies.count()
    if feed.series <> invalid then seriesCount = feed.series.count()
    print "[MM] FeedTask: parsed feed — " ; str(movieCount).trim() ; " movies, " ; str(seriesCount).trim() ; " series"

    ' Build content tree
    contentTree = buildContentTree(feed)

    rowCount = contentTree.getChildCount()
    print "[MM] FeedTask: content tree built — " ; str(rowCount).trim() ; " rows"

    m.top.feedContent = contentTree
end sub

function buildContentTree(feed as object) as object
    root = createObject("roSGNode", "ContentNode")

    ' Movies row
    moviesRow = createObject("roSGNode", "ContentNode")
    moviesRow.title = "Movies"

    movies = feed.movies
    if movies <> invalid
        for each movie in movies
            movieNode = buildMovieNode(movie)
            if movieNode <> invalid
                moviesRow.appendChild(movieNode)
            end if
        end for
    end if

    ' TV Series row
    seriesRow = createObject("roSGNode", "ContentNode")
    seriesRow.title = "TV Series"

    series = feed.series
    if series <> invalid
        for each show in series
            showNode = buildSeriesNode(show)
            if showNode <> invalid
                seriesRow.appendChild(showNode)
            end if
        end for
    end if

    ' Only add rows that have content
    if moviesRow.getChildCount() > 0
        root.appendChild(moviesRow)
    end if

    if seriesRow.getChildCount() > 0
        root.appendChild(seriesRow)
    end if

    return root
end function

function buildMovieNode(movie as object) as object
    node = createObject("roSGNode", "ContentNode")

    node.id = safeStr(movie.id)
    node.title = safeStr(movie.title)
    node.description = safeStr(movie.shortDescription)

    if movie.thumbnail <> invalid
        node.HDPosterUrl = movie.thumbnail
    end if

    ' Custom fields
    node.addField("itemType", "string", false)
    node.itemType = "movie"

    node.addField("releaseDate", "string", false)
    node.releaseDate = safeStr(movie.releaseDate)

    node.addField("genres", "stringarray", false)
    if movie.genres <> invalid
        genreArr = []
        for each g in movie.genres
            genreArr.push(g)
        end for
        node.genres = genreArr
    end if

    node.addField("cast", "stringarray", false)
    if movie.tags <> invalid and movie.tags.cast <> invalid
        castArr = []
        for each c in movie.tags.cast
            castArr.push(c)
        end for
        node.cast = castArr
    end if

    ' Stream URL from content.videos[0].url
    node.addField("streamUrl", "string", false)
    if movie.content <> invalid and movie.content.videos <> invalid
        videos = movie.content.videos
        if videos.count() > 0
            node.streamUrl = safeStr(videos[0].url)
        end if
    end if

    ' Subtitle URL (SRT)
    node.addField("subtitleUrl", "string", false)
    if movie.content <> invalid and movie.content.subtitleUrl <> invalid
        node.subtitleUrl = safeStr(movie.content.subtitleUrl)
    end if

    ' Server-side playback position (seconds)
    node.addField("playbackPosition", "integer", false)
    if movie.content <> invalid and movie.content.playbackPosition <> invalid
        node.playbackPosition = movie.content.playbackPosition
    end if

    return node
end function

function buildSeriesNode(show as object) as object
    node = createObject("roSGNode", "ContentNode")

    node.id = safeStr(show.id)
    node.title = safeStr(show.title)
    node.description = safeStr(show.shortDescription)

    if show.thumbnail <> invalid
        node.HDPosterUrl = show.thumbnail
    end if

    ' Custom fields
    node.addField("itemType", "string", false)
    node.itemType = "series"

    node.addField("releaseDate", "string", false)
    node.releaseDate = safeStr(show.releaseDate)

    node.addField("genres", "stringarray", false)
    if show.genres <> invalid
        genreArr = []
        for each g in show.genres
            genreArr.push(g)
        end for
        node.genres = genreArr
    end if

    node.addField("cast", "stringarray", false)
    if show.tags <> invalid and show.tags.cast <> invalid
        castArr = []
        for each c in show.tags.cast
            castArr.push(c)
        end for
        node.cast = castArr
    end if

    ' Build season/episode tree
    if show.seasons <> invalid
        for each season in show.seasons
            seasonNode = createObject("roSGNode", "ContentNode")

            seasonNode.addField("seasonNumber", "string", false)
            seasonNode.seasonNumber = safeStr(season.seasonNumber)

            if season.episodes <> invalid
                for each ep in season.episodes
                    epNode = createObject("roSGNode", "ContentNode")
                    epNode.id = safeStr(ep.id)
                    epNode.title = safeStr(ep.title)
                    epNode.description = safeStr(ep.shortDescription)

                    if ep.thumbnail <> invalid
                        epNode.HDPosterUrl = ep.thumbnail
                    end if

                    epNode.addField("episodeNumber", "string", false)
                    epNode.episodeNumber = safeStr(ep.episodeNumber)

                    epNode.addField("streamUrl", "string", false)
                    if ep.content <> invalid and ep.content.videos <> invalid
                        videos = ep.content.videos
                        if videos.count() > 0
                            epNode.streamUrl = safeStr(videos[0].url)
                        end if
                    end if

                    ' Subtitle URL (SRT)
                    epNode.addField("subtitleUrl", "string", false)
                    if ep.content <> invalid and ep.content.subtitleUrl <> invalid
                        epNode.subtitleUrl = safeStr(ep.content.subtitleUrl)
                    end if

                    ' Server-side playback position (seconds)
                    epNode.addField("playbackPosition", "integer", false)
                    if ep.content <> invalid and ep.content.playbackPosition <> invalid
                        epNode.playbackPosition = ep.content.playbackPosition
                    end if

                    seasonNode.appendChild(epNode)
                end for
            end if

            node.appendChild(seasonNode)
        end for
    end if

    return node
end function

function safeStr(value as dynamic) as string
    if value = invalid then return ""
    return value.toStr()
end function

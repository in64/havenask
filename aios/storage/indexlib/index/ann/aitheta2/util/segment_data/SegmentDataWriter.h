/*
 * Copyright 2014-present Alibaba Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#pragma once

#include "indexlib/file_system/Directory.h"
#include "indexlib/file_system/file/FileWriter.h"
#include "indexlib/index/ann/aitheta2/CommonDefine.h"
#include "indexlib/index/ann/aitheta2/util/segment_data/IndexDataAddrHolder.h"

namespace indexlibv2::index::ann {

class IndexDataWriter;
typedef std::shared_ptr<IndexDataWriter> IndexDataWriterPtr;

class SegmentDataWriter
{
    friend IndexDataWriter;

public:
    SegmentDataWriter();
    ~SegmentDataWriter();

public:
    bool Init(const indexlib::file_system::DirectoryPtr& directory);
    IndexDataWriterPtr GetIndexDataWriter(index_id_t id);
    indexlib::file_system::FileWriterPtr GetPrimaryKeyWriter();
    indexlib::file_system::FileWriterPtr GetEmbeddingDataWriter();
    size_t GetSegmentSize() const { return _indexFileWriter->GetLength(); }
    bool Close();
    bool IsClosed() const { return _isClosed; }

private:
    size_t WriteIndexFile(const void* buf, size_t len) { return _indexFileWriter->Write(buf, len).GetOrThrow(); }

private:
    indexlib::file_system::DirectoryPtr _directory;
    indexlib::file_system::FileWriterPtr _indexFileWriter;
    indexlib::file_system::FileWriterPtr _primaryKeyWriter;
    indexlib::file_system::FileWriterPtr _embeddingDataWriter;
    IndexDataAddrHolder _indexDataAddrHolder;
    IndexDataWriterPtr _currentIndexDataWriter;
    bool _isClosed {false};

private:
    AUTIL_LOG_DECLARE();
};

class IndexDataWriter
{
public:
    IndexDataWriter(SegmentDataWriter* writer, index_id_t id)
        : _segmentDataWriter(writer)
        , _indexId(id)
        , _isClosed(false)
    {
        _indexDataAddress.offset = _segmentDataWriter->_indexFileWriter->GetLength();
        _indexDataAddress.length = 0;
    }
    ~IndexDataWriter() { Close(); }

public:
    size_t Write(const void* buf, size_t len)
    {
        len = _segmentDataWriter->WriteIndexFile(buf, len);
        _indexDataAddress.length += len;
        return len;
    }
    size_t GetLength() const { return _indexDataAddress.length; }
    bool Close()
    {
        if (_isClosed) {
            return true;
        }
        _segmentDataWriter->_indexDataAddrHolder.AddAddr(_indexId, _indexDataAddress);
        _indexId = kInvalidIndexId;
        _indexDataAddress.offset = 0;
        _indexDataAddress.length = 0;
        _segmentDataWriter = nullptr;
        _isClosed = true;
        return true;
    }
    bool IsClosed() const { return _isClosed; }

private:
    SegmentDataWriter* _segmentDataWriter;
    index_id_t _indexId;
    IndexDataAddr _indexDataAddress;
    bool _isClosed;
    AUTIL_LOG_DECLARE();
};

typedef std::shared_ptr<SegmentDataWriter> SegmentDataWriterPtr;

} // namespace indexlibv2::index::ann

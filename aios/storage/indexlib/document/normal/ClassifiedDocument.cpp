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
#include "indexlib/document/normal/ClassifiedDocument.h"

#include "indexlib/document/RawDocument.h"
#include "indexlib/document/normal/AttributeDocument.h"
#include "indexlib/document/normal/IndexDocument.h"
#include "indexlib/document/normal/SerializedSummaryDocument.h"
#include "indexlib/document/normal/SourceDocument.h"
#include "indexlib/document/normal/SummaryDocument.h"
#include "indexlib/document/normal/SummaryFormatter.h"
#include "indexlib/index/inverted_index/config/PayloadConfig.h"
#include "indexlib/index/summary/config/SummaryIndexConfig.h"

using namespace std;
using namespace autil;
using namespace indexlib::document;

namespace indexlibv2 { namespace document {
AUTIL_LOG_SETUP(indexlib.document, ClassifiedDocument);

const uint32_t ClassifiedDocument::MAX_SECTION_LENGTH = Section::MAX_SECTION_LENGTH;
const uint32_t ClassifiedDocument::MAX_TOKEN_PER_SECTION = Section::MAX_TOKEN_PER_SECTION;
const uint32_t ClassifiedDocument::MAX_SECTION_PER_FIELD = Field::MAX_SECTION_PER_FIELD;

ClassifiedDocument::ClassifiedDocument()
{
    _pool.reset(new PoolType(1024));
    _indexDocument.reset(new IndexDocument(_pool.get()));
    _summaryDocument.reset(new SummaryDocument());
    _attributeDocument.reset(new AttributeDocument());
    _maxSectionLen = MAX_SECTION_LENGTH;
    _maxSectionPerField = MAX_SECTION_PER_FIELD;
}

ClassifiedDocument::~ClassifiedDocument()
{
    _indexDocument.reset();
    _summaryDocument.reset();
    _attributeDocument.reset();
    _pool.reset();
}

Field* ClassifiedDocument::createIndexField(fieldid_t fieldId, Field::FieldTag fieldTag)
{
    assert(_indexDocument);
    return _indexDocument->CreateField(fieldId, fieldTag);
}

Section* ClassifiedDocument::createSection(IndexTokenizeField* field, uint32_t tokenNum, section_weight_t sectionWeight)
{
    Section* indexSection = field->CreateSection(tokenNum);
    if (nullptr == indexSection) {
        return nullptr;
    }

    indexSection->SetWeight(sectionWeight);
    if (field->GetSectionCount() >= _maxSectionPerField) {
        return nullptr;
    }
    return indexSection;
}

void ClassifiedDocument::setMaxSectionLen(section_len_t maxSectionLen)
{
    if (maxSectionLen < MAX_SECTION_LENGTH && maxSectionLen > 1) {
        _maxSectionLen = maxSectionLen;
    } else {
        _maxSectionLen = MAX_SECTION_LENGTH;
    }
}

void ClassifiedDocument::setMaxSectionPerField(uint32_t maxSectionPerField)
{
    if (maxSectionPerField < MAX_SECTION_PER_FIELD && maxSectionPerField > 0) {
        _maxSectionPerField = maxSectionPerField;
    } else {
        _maxSectionPerField = MAX_SECTION_PER_FIELD;
    }
}

// Status ClassifiedDocument::serializeSummaryDocument(const std::shared_ptr<config::SummaryIndexConfig>& summaryConfig)
// {
//     SummaryFormatter summaryFormatter(summaryConfig);
//     return summaryFormatter.SerializeSummaryDoc(_summaryDocument, _serSummaryDoc);
// }

void ClassifiedDocument::setAttributeField(fieldid_t id, const string& value)
{
    if (_attributeDocument) {
        _attributeDocument->SetField(id, autil::MakeCString(value, _pool.get()));
    }
}

void ClassifiedDocument::setAttributeField(fieldid_t id, const char* value, size_t len)
{
    if (_attributeDocument) {
        _attributeDocument->SetField(id, autil::MakeCString(value, len, _pool.get()));
    }
}

void ClassifiedDocument::setAttributeFieldNoCopy(fieldid_t id, const StringView& value)
{
    if (_attributeDocument) {
        _attributeDocument->SetField(id, value);
    }
}

const StringView& ClassifiedDocument::getAttributeField(fieldid_t fieldId)
{
    if (_attributeDocument) {
        return _attributeDocument->GetField(fieldId);
    }
    return StringView::empty_instance();
}

void ClassifiedDocument::setSummaryField(fieldid_t id, const string& value)
{
    if (_summaryDocument) {
        _summaryDocument->SetField(id, autil::MakeCString(value, _pool.get()));
    }
}

void ClassifiedDocument::setSummaryField(fieldid_t id, const char* value, size_t len)
{
    if (_summaryDocument) {
        _summaryDocument->SetField(id, autil::MakeCString(value, len, _pool.get()));
    }
}

void ClassifiedDocument::setSummaryFieldNoCopy(fieldid_t id, const StringView& value)
{
    if (_summaryDocument) {
        _summaryDocument->SetField(id, value);
    }
}

const StringView& ClassifiedDocument::getSummaryField(fieldid_t fieldId)
{
    if (_summaryDocument) {
        return _summaryDocument->GetField(fieldId);
    }
    return StringView::empty_instance();
}

termpayload_t ClassifiedDocument::getTermPayload(const string& termText) const
{
    assert(_indexDocument);
    return _indexDocument->GetTermPayload(termText);
}

termpayload_t ClassifiedDocument::getTermPayload(uint64_t intKey) const
{
    assert(_indexDocument);
    return _indexDocument->GetTermPayload(intKey);
}

void ClassifiedDocument::setTermPayload(const string& termText, termpayload_t payload)
{
    assert(_indexDocument);
    _indexDocument->SetTermPayload(termText, payload);
}

void ClassifiedDocument::setTermPayload(uint64_t intKey, termpayload_t payload)
{
    assert(_indexDocument);
    _indexDocument->SetTermPayload(intKey, payload);
}
docpayload_t ClassifiedDocument::getTermDocPayload(const string& termText) const
{
    assert(_indexDocument);
    return _indexDocument->GetDocPayload(termText);
}

docpayload_t ClassifiedDocument::getTermDocPayload(uint64_t intKey) const
{
    assert(_indexDocument);
    return _indexDocument->GetDocPayload(intKey);
}

void ClassifiedDocument::setTermDocPayload(const string& termText, docpayload_t docPayload)
{
    assert(_indexDocument);
    _indexDocument->SetDocPayload(termText, docPayload);
}

void ClassifiedDocument::setTermDocPayload(uint64_t intKey, docpayload_t docPayload)
{
    assert(_indexDocument);
    _indexDocument->SetDocPayload(intKey, docPayload);
}

docpayload_t ClassifiedDocument::getTermDocPayload(const indexlib::config::PayloadConfig& payloadConfig,
                                                   const string& termText) const
{
    assert(_indexDocument);
    return _indexDocument->GetDocPayload(payloadConfig, termText);
}

void ClassifiedDocument::setTermDocPayload(const indexlib::config::PayloadConfig& payloadConfig, const string& termText,
                                           docpayload_t docPayload)
{
    assert(_indexDocument);
    _indexDocument->SetDocPayload(payloadConfig, termText, docPayload);
}

void ClassifiedDocument::setPrimaryKey(const string& primaryKey)
{
    assert(_indexDocument);
    _indexDocument->SetPrimaryKey(primaryKey);
}

const string& ClassifiedDocument::getPrimaryKey()
{
    assert(_indexDocument);
    return _indexDocument->GetPrimaryKey();
}

void ClassifiedDocument::clear()
{
    _indexDocument.reset();
    _summaryDocument.reset();
    _attributeDocument.reset();
    _serSummaryDoc.reset();
    _srcDocument.reset();
    _pool.reset();
}

void ClassifiedDocument::setSourceDocument(const std::shared_ptr<indexlib::document::SourceDocument>& sourceDocument)
{
    _srcDocument = sourceDocument;
}

void ClassifiedDocument::createSourceDocument(const std::vector<std::vector<std::string>>& fieldGroups,
                                              const std::shared_ptr<RawDocument>& rawDoc)
{
    _srcDocument.reset(new indexlib::document::SourceDocument(_pool.get()));
    for (size_t i = 0; i < fieldGroups.size(); i++) {
        for (auto& field : fieldGroups[i]) {
            autil::StringView fn(field);
            if (!rawDoc->exist(fn)) {
                _srcDocument->AppendNonExistField(i, field);
                continue;
            }
            const autil::StringView& value = rawDoc->getField(fn);
            // no need to copy value from rawDoc
            _srcDocument->Append(i, field, value, false);
        }
    }
}

void ClassifiedDocument::sourceDocumentAppendAccessaryFields(const std::vector<std::string>& fieldNames,
                                                             const std::shared_ptr<RawDocument>& rawDoc)
{
    if (fieldNames.empty()) {
        return;
    }
    if (!_srcDocument) {
        _srcDocument.reset(new indexlib::document::SourceDocument(_pool.get()));
    }
    for (auto& field : fieldNames) {
        autil::StringView fn(field);
        if (!rawDoc->exist(fn)) {
            _srcDocument->AppendNonExistAccessaryField(field);
            continue;
        }
        const autil::StringView& value = rawDoc->getField(fn);
        _srcDocument->AppendAccessaryField(field, value, false);
    }
}

}} // namespace indexlibv2::document

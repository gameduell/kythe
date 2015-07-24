/*
 * Copyright 2014 Google Inc. All rights reserved.
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

#include "KytheGraphObserver.h"

#include "clang/AST/Attr.h"
#include "clang/AST/Decl.h"
#include "clang/AST/DeclCXX.h"
#include "clang/AST/DeclFriend.h"
#include "clang/AST/DeclObjC.h"
#include "clang/AST/DeclOpenMP.h"
#include "clang/AST/DeclTemplate.h"
#include "clang/AST/Expr.h"
#include "clang/AST/ExprCXX.h"
#include "clang/AST/ExprObjC.h"
#include "clang/AST/NestedNameSpecifier.h"
#include "clang/AST/Stmt.h"
#include "clang/AST/StmtCXX.h"
#include "clang/AST/StmtObjC.h"
#include "clang/AST/StmtOpenMP.h"
#include "clang/AST/TemplateBase.h"
#include "clang/AST/TemplateName.h"
#include "clang/AST/Type.h"
#include "clang/AST/TypeLoc.h"
#include "clang/Basic/SourceManager.h"
#include "kythe/cxx/common/path_utils.h"

#include "IndexerASTHooks.h"
#include "KytheGraphRecorder.h"

namespace kythe {

using clang::SourceLocation;
using kythe::proto::Entry;
using llvm::StringRef;

static const char *CompletenessToString(
    KytheGraphObserver::Completeness completeness) {
  switch (completeness) {
    case KytheGraphObserver::Completeness::Definition:
      return "definition";
    case KytheGraphObserver::Completeness::Complete:
      return "complete";
    case KytheGraphObserver::Completeness::Incomplete:
      return "incomplete";
  }
  assert(0 && "Invalid enumerator passed to CompletenessToString.");
  return "invalid-completeness";
}

kythe::proto::VName KytheGraphObserver::VNameFromFileEntry(
    const clang::FileEntry *file_entry) {
  kythe::proto::VName out_name;
  if (!vfs_->get_vname(file_entry, &out_name)) {
    out_name.set_language("c++");
    llvm::StringRef working_directory = vfs_->working_directory();
    llvm::StringRef file_name(file_entry->getName());
    if (file_name.startswith(working_directory)) {
      out_name.set_path(RelativizePath(file_name, working_directory));
    } else {
      out_name.set_path(file_entry->getName());
    }
  }
  return out_name;
}

void KytheGraphObserver::AppendFileBufferSliceHashToStream(
    clang::SourceLocation loc, llvm::raw_ostream &Ostream) {
  // TODO(zarko): Does this mechanism produce sufficiently unique
  // identifiers? Ideally, we would hash the full buffer segment into
  // which `loc` points, then record `loc`'s offset.
  bool was_invalid = false;
  auto *buffer = SourceManager->getCharacterData(loc, &was_invalid);
  size_t offset = SourceManager->getFileOffset(loc);
  if (was_invalid) {
    Ostream << "!invalid[" << offset << "]";
    return;
  }
  auto loc_end = clang::Lexer::getLocForEndOfToken(
      loc, 0 /* offset from end of token */, *SourceManager, *getLangOptions());
  size_t offset_end = SourceManager->getFileOffset(loc_end);
  Ostream << HashToString(
      llvm::hash_value(llvm::StringRef(buffer, offset_end - offset)));
}

void KytheGraphObserver::AppendFullLocationToStream(
    std::vector<clang::FileID> *posted_fileids, clang::SourceLocation loc,
    llvm::raw_ostream &Ostream) {
  if (!loc.isValid()) {
    Ostream << "invalid";
    return;
  }
  if (loc.isFileID()) {
    clang::FileID file_id = SourceManager->getFileID(loc);
    const clang::FileEntry *file_entry =
        SourceManager->getFileEntryForID(file_id);
    // Don't use getPresumedLoc() since we want to ignore #line-style
    // directives.
    if (file_entry) {
      size_t offset = SourceManager->getFileOffset(loc);
      Ostream << offset;
    } else {
      AppendFileBufferSliceHashToStream(loc, Ostream);
    }
    size_t file_id_count = posted_fileids->size();
    // Don't inline the same fileid multiple times.
    // Right now we don't emit preprocessor version information, but we
    // do distinguish between FileIDs for the same FileEntry.
    for (size_t old_id = 0; old_id < file_id_count; ++old_id) {
      if (file_id == (*posted_fileids)[old_id]) {
        Ostream << "@." << old_id;
        return;
      }
    }
    posted_fileids->push_back(file_id);
    if (file_entry) {
      kythe::proto::VName file_vname(VNameFromFileEntry(file_entry));
      if (!file_vname.corpus().empty()) {
        Ostream << file_vname.corpus() << "/";
      }
      if (!file_vname.root().empty()) {
        Ostream << file_vname.root() << "/";
      }
      Ostream << file_vname.path();
    }
  } else {
    AppendFullLocationToStream(posted_fileids,
                               SourceManager->getExpansionLoc(loc), Ostream);
    Ostream << "@";
    AppendFullLocationToStream(posted_fileids,
                               SourceManager->getSpellingLoc(loc), Ostream);
  }
}

bool KytheGraphObserver::AppendRangeToStream(llvm::raw_ostream &Ostream,
                                             const Range &Range) {
  std::vector<clang::FileID> posted_fileids;
  // We want to override this here so that the names we use are filtered
  // through the vname definitions we got from the compilation unit.
  if (Range.PhysicalRange.isInvalid()) {
    return false;
  }
  AppendFullLocationToStream(&posted_fileids, Range.PhysicalRange.getBegin(),
                             Ostream);
  if (Range.PhysicalRange.getEnd() != Range.PhysicalRange.getBegin()) {
    AppendFullLocationToStream(&posted_fileids, Range.PhysicalRange.getEnd(),
                               Ostream);
  }
  if (Range.Kind == GraphObserver::Range::RangeKind::Wraith) {
    Ostream << Range.Context.ToClaimedString();
  }
  return true;
}

/// \brief Attempt to associate a `SourceLocation` with a `FileEntry` by
/// searching through the location's macro expansion history.
/// \param loc The location to associate. Any `SourceLocation` is acceptable.
/// \param source_manager The `SourceManager` that generated `loc`.
/// \return a `FileEntry` if one was found, null otherwise.
static const clang::FileEntry *SearchForFileEntry(
    clang::SourceLocation loc, clang::SourceManager *source_manager) {
  clang::FileID file_id = source_manager->getFileID(loc);
  const clang::FileEntry *out = loc.isFileID() && loc.isValid()
                                    ? source_manager->getFileEntryForID(file_id)
                                    : nullptr;
  if (out) {
    return out;
  }
  auto expansion = source_manager->getExpansionLoc(loc);
  if (expansion.isValid() && expansion != loc) {
    out = SearchForFileEntry(expansion, source_manager);
    if (out) {
      return out;
    }
  }
  auto spelling = source_manager->getSpellingLoc(loc);
  if (spelling.isValid() && spelling != loc) {
    out = SearchForFileEntry(spelling, source_manager);
  }
  return out;
}

kythe::proto::VName KytheGraphObserver::VNameFromRange(
    const GraphObserver::Range &range) {
  const clang::SourceRange &source_range = range.PhysicalRange;
  clang::SourceLocation begin = source_range.getBegin();
  clang::SourceLocation end = source_range.getEnd();
  assert(begin.isValid());
  assert(end.isValid());
  if (begin.isMacroID()) {
    begin = SourceManager->getExpansionLoc(begin);
  }
  if (end.isMacroID()) {
    end = SourceManager->getExpansionLoc(end);
  }
  kythe::proto::VName out_name;
  if (const clang::FileEntry *file_entry =
          SearchForFileEntry(begin, SourceManager)) {
    out_name.CopyFrom(VNameFromFileEntry(file_entry));
  } else if (range.Kind == GraphObserver::Range::RangeKind::Wraith) {
    out_name.CopyFrom(VNameFromNodeId(range.Context));
  } else {
    out_name.set_language("c++");
  }
  size_t begin_offset = SourceManager->getFileOffset(begin);
  size_t end_offset = SourceManager->getFileOffset(end);
  auto *const signature = out_name.mutable_signature();
  signature->append("@");
  signature->append(std::to_string(begin_offset));
  signature->append(":");
  signature->append(std::to_string(end_offset));
  if (range.Kind == GraphObserver::Range::RangeKind::Wraith) {
    signature->append("@");
    signature->append(range.Context.ToClaimedString());
  }
  return out_name;
}

void KytheGraphObserver::RecordSourceLocation(
    clang::SourceLocation source_location, PropertyID offset_id) {
  if (source_location.isMacroID()) {
    source_location = SourceManager->getExpansionLoc(source_location);
  }
  size_t offset = SourceManager->getFileOffset(source_location);
  recorder_->AddProperty(offset_id, offset);
}

void KytheGraphObserver::recordMacroNode(const NodeId &macro_id) {
  recorder_->BeginNode(VNameFromNodeId(macro_id), NodeKindID::kMacro);
  recorder_->EndNode();
}

void KytheGraphObserver::recordExpandsRange(const Range &source_range,
                                            const NodeId &macro_id) {
  RecordAnchor(source_range, macro_id, EdgeKindID::kRefExpands,
               Claimability::Claimable);
}

void KytheGraphObserver::recordIndirectlyExpandsRange(const Range &source_range,
                                                      const NodeId &macro_id) {
  RecordAnchor(source_range, macro_id, EdgeKindID::kRefExpandsTransitive,
               Claimability::Claimable);
}

void KytheGraphObserver::recordUndefinesRange(const Range &source_range,
                                              const NodeId &macro_id) {
  RecordAnchor(source_range, macro_id, EdgeKindID::kUndefines,
               Claimability::Claimable);
}

void KytheGraphObserver::recordBoundQueryRange(const Range &source_range,
                                               const NodeId &macro_id) {
  RecordAnchor(source_range, macro_id, EdgeKindID::kRefQueries,
               Claimability::Claimable);
}

void KytheGraphObserver::recordUnboundQueryRange(const Range &source_range,
                                                 const NameId &macro_name) {
  RecordAnchor(source_range, RecordName(macro_name), EdgeKindID::kRefQueries,
               Claimability::Claimable);
}

void KytheGraphObserver::recordIncludesRange(const Range &source_range,
                                             const clang::FileEntry *File) {
  RecordAnchor(source_range, VNameFromFileEntry(File), EdgeKindID::kRefIncludes,
               Claimability::Claimable);
}

void KytheGraphObserver::recordUserDefinedNode(const NameId &name,
                                               const NodeId &node,
                                               const llvm::StringRef &kind,
                                               Completeness completeness) {
  KytheGraphRecorder::VName name_vname(RecordName(name));
  KytheGraphRecorder::VName node_vname(VNameFromNodeId(node));
  recorder_->BeginNode(node_vname, kind);
  recorder_->AddProperty(PropertyID::kComplete,
                         CompletenessToString(completeness));
  recorder_->EndNode();
  recorder_->AddEdge(node_vname, EdgeKindID::kNamed, name_vname);
}

void KytheGraphObserver::recordVariableNode(const NameId &name,
                                            const NodeId &node,
                                            Completeness completeness) {
  KytheGraphRecorder::VName name_vname(RecordName(name));
  KytheGraphRecorder::VName node_vname(VNameFromNodeId(node));
  recorder_->BeginNode(node_vname, NodeKindID::kVariable);
  recorder_->AddProperty(PropertyID::kComplete,
                         CompletenessToString(completeness));
  recorder_->EndNode();
  recorder_->AddEdge(node_vname, EdgeKindID::kNamed, name_vname);
}

void KytheGraphObserver::RecordDeferredNodes() {
  for (const auto &range : deferred_anchors_) {
    KytheGraphRecorder::VName anchor_name(VNameFromRange(range));
    recorder_->BeginNode(anchor_name, NodeKindID::kAnchor);
    RecordSourceLocation(range.PhysicalRange.getBegin(),
                         PropertyID::kLocationStartOffset);
    RecordSourceLocation(range.PhysicalRange.getEnd(),
                         PropertyID::kLocationEndOffset);
    recorder_->EndNode();
    if (const auto *file_entry = SourceManager->getFileEntryForID(
            SourceManager->getFileID(range.PhysicalRange.getBegin()))) {
      recorder_->AddEdge(anchor_name, EdgeKindID::kChildOf,
                         VNameFromFileEntry(file_entry));
    }
    if (range.Kind == GraphObserver::Range::RangeKind::Wraith) {
      recorder_->AddEdge(anchor_name, EdgeKindID::kChildOf,
                         VNameFromNodeId(range.Context));
    }
  }
  deferred_anchors_.clear();
}

KytheGraphRecorder::VName KytheGraphObserver::RecordAnchor(
    const GraphObserver::Range &source_range,
    const GraphObserver::NodeId &primary_anchored_to,
    EdgeKindID anchor_edge_kind, Claimability cl) {
  assert(!file_stack_.empty());
  KytheGraphRecorder::VName anchor_name(VNameFromRange(source_range));
  if (claimRange(source_range) || claimNode(primary_anchored_to)) {
    deferred_anchors_.insert(source_range);
    cl = Claimability::Unclaimable;
  }
  if (cl == Claimability::Unclaimable) {
    recorder_->AddEdge(anchor_name, anchor_edge_kind,
                       VNameFromNodeId(primary_anchored_to));
  }
  return anchor_name;
}

KytheGraphRecorder::VName KytheGraphObserver::RecordAnchor(
    const GraphObserver::Range &source_range,
    const kythe::proto::VName &primary_anchored_to, EdgeKindID anchor_edge_kind,
    Claimability cl) {
  assert(!file_stack_.empty());
  KytheGraphRecorder::VName anchor_name(VNameFromRange(source_range));
  if (claimRange(source_range)) {
    deferred_anchors_.insert(source_range);
    cl = Claimability::Unclaimable;
  }
  if (cl == Claimability::Unclaimable) {
    recorder_->AddEdge(anchor_name, anchor_edge_kind, primary_anchored_to);
  }
  return anchor_name;
}

void KytheGraphObserver::recordCallEdge(
    const GraphObserver::Range &source_range, const NodeId &caller_id,
    const NodeId &callee_id) {
  KytheGraphRecorder::VName anchor_name(RecordAnchor(
      source_range, caller_id, EdgeKindID::kChildOf, Claimability::Claimable));
  recorder_->AddEdge(anchor_name, EdgeKindID::kRefCall,
                     VNameFromNodeId(callee_id));
}

kythe::proto::VName KytheGraphObserver::VNameFromNodeId(
    const GraphObserver::NodeId &node_id) {
  KytheGraphRecorder::VName out_vname;
  out_vname.set_language("c++");
  if (const auto *token = clang::dyn_cast<KytheClaimToken>(node_id.Token)) {
    token->DecorateVName(&out_vname);
  }
  out_vname.set_signature(node_id.ToString());
  return out_vname;
}

kythe::proto::VName KytheGraphObserver::RecordName(
    const GraphObserver::NameId &name_id) {
  KytheGraphRecorder::VName out_vname;
  // Names don't have corpus, path or root set.
  out_vname.set_language("c++");
  const std::string name_id_string = name_id.ToString();
  out_vname.set_signature(name_id_string);
  if (written_name_ids_.insert(name_id_string).second) {
    recorder_->BeginNode(out_vname, NodeKindID::kName);
    recorder_->EndNode();
  }
  return out_vname;
}

void KytheGraphObserver::recordParamEdge(const NodeId &param_of_id,
                                         uint32_t ordinal,
                                         const NodeId &param_id) {
  recorder_->AddEdge(VNameFromNodeId(param_of_id), EdgeKindID::kParam,
                     VNameFromNodeId(param_id), ordinal);
}

void KytheGraphObserver::recordChildOfEdge(const NodeId &child_id,
                                           const NodeId &parent_id) {
  recorder_->AddEdge(VNameFromNodeId(child_id), EdgeKindID::kChildOf,
                     VNameFromNodeId(parent_id));
}

void KytheGraphObserver::recordTypeEdge(const NodeId &term_id,
                                        const NodeId &type_id) {
  recorder_->AddEdge(VNameFromNodeId(term_id), EdgeKindID::kHasType,
                     VNameFromNodeId(type_id));
}

void KytheGraphObserver::recordCallableAsEdge(const NodeId &from_id,
                                              const NodeId &to_id) {
  recorder_->AddEdge(VNameFromNodeId(from_id), EdgeKindID::kCallableAs,
                     VNameFromNodeId(to_id));
}

void KytheGraphObserver::recordSpecEdge(const NodeId &term_id,
                                        const NodeId &type_id) {
  recorder_->AddEdge(VNameFromNodeId(term_id), EdgeKindID::kSpecializes,
                     VNameFromNodeId(type_id));
}

void KytheGraphObserver::recordInstEdge(const NodeId &term_id,
                                        const NodeId &type_id) {
  recorder_->AddEdge(VNameFromNodeId(term_id), EdgeKindID::kInstantiates,
                     VNameFromNodeId(type_id));
}

GraphObserver::NodeId KytheGraphObserver::nodeIdForTypeAliasNode(
    const NameId &alias_name, const NodeId &aliased_type) {
  NodeId id_out(&type_token_);
  id_out.Identity = "talias(" + alias_name.ToString() + "," +
                    aliased_type.ToClaimedString() + ")";
  return id_out;
}

GraphObserver::NodeId KytheGraphObserver::recordTypeAliasNode(
    const NameId &alias_name, const NodeId &aliased_type) {
  NodeId type_id = nodeIdForTypeAliasNode(alias_name, aliased_type);
  if (written_types_.insert(type_id.ToClaimedString()).second) {
    kythe::proto::VName type_vname(VNameFromNodeId(type_id));
    recorder_->BeginNode(type_vname, NodeKindID::kTAlias);
    recorder_->EndNode();
    kythe::proto::VName alias_name_vname(RecordName(alias_name));
    recorder_->AddEdge(type_vname, EdgeKindID::kNamed, alias_name_vname);
    kythe::proto::VName aliased_type_vname(VNameFromNodeId(aliased_type));
    recorder_->AddEdge(type_vname, EdgeKindID::kAliases, aliased_type_vname);
  }
  return type_id;
}

void KytheGraphObserver::recordDefinitionRange(
    const GraphObserver::Range &source_range, const NodeId &node) {
  RecordAnchor(source_range, node, EdgeKindID::kDefines,
               Claimability::Claimable);
}

void KytheGraphObserver::recordCompletionRange(
    const GraphObserver::Range &source_range, const NodeId &node,
    Specificity spec) {
  RecordAnchor(source_range, node, spec == Specificity::UniquelyCompletes
                                       ? EdgeKindID::kUniquelyCompletes
                                       : EdgeKindID::kCompletes,
               Claimability::Unclaimable);
}

void KytheGraphObserver::recordNamedEdge(const NodeId &node,
                                         const NameId &name) {
  recorder_->AddEdge(VNameFromNodeId(node), EdgeKindID::kNamed,
                     RecordName(name));
}

GraphObserver::NodeId KytheGraphObserver::nodeIdForNominalTypeNode(
    const NameId &name_id) {
  NodeId id_out(&type_token_);
  // Appending #t to a name produces the VName signature of the nominal
  // type node referring to that name. For example, the VName for a
  // forward-declared class type will look like "C#c#t".
  id_out.Identity = name_id.ToString() + "#t";
  return id_out;
}

GraphObserver::NodeId KytheGraphObserver::recordNominalTypeNode(
    const NameId &name_id) {
  NodeId id_out = nodeIdForNominalTypeNode(name_id);
  if (written_types_.insert(id_out.ToClaimedString()).second) {
    kythe::proto::VName type_vname(VNameFromNodeId(id_out));
    recorder_->BeginNode(type_vname, NodeKindID::kTNominal);
    recorder_->EndNode();
    recorder_->AddEdge(type_vname, EdgeKindID::kNamed, RecordName(name_id));
  }
  return id_out;
}

GraphObserver::NodeId KytheGraphObserver::recordTappNode(
    const NodeId &tycon_id, const std::vector<const NodeId *> &params) {
  GraphObserver::NodeId id_out(&type_token_);
  // We can't just use juxtaposition here because it leads to ambiguity
  // as we can't assume that we have kind information, eg
  //   foo bar baz
  // might be
  //   foo (bar baz)
  // We'll turn it into a C-style function application:
  //   foo(bar,baz) || foo(bar(baz))
  bool comma = false;
  id_out.Identity = tycon_id.ToClaimedString();
  id_out.Identity.append("(");
  for (const auto *next_id : params) {
    if (comma) {
      id_out.Identity.append(",");
    }
    id_out.Identity.append(next_id->ToClaimedString());
    comma = true;
  }
  id_out.Identity.append(")");
  if (written_types_.insert(id_out.ToClaimedString()).second) {
    kythe::proto::VName tapp_vname(VNameFromNodeId(id_out));
    recorder_->BeginNode(tapp_vname, NodeKindID::kTApp);
    recorder_->EndNode();
    recorder_->AddEdge(tapp_vname, EdgeKindID::kParam,
                       VNameFromNodeId(tycon_id), 0);
    for (uint32_t param_index = 0; param_index < params.size(); ++param_index) {
      recorder_->AddEdge(tapp_vname, EdgeKindID::kParam,
                         VNameFromNodeId(*params[param_index]),
                         param_index + 1);
    }
  }
  return id_out;
}

void KytheGraphObserver::recordEnumNode(const NodeId &node_id,
                                        Completeness completeness,
                                        EnumKind enum_kind) {
  recorder_->BeginNode(VNameFromNodeId(node_id), NodeKindID::kSum);
  recorder_->AddProperty(PropertyID::kComplete,
                         CompletenessToString(completeness));
  recorder_->AddProperty(PropertyID::kSubkind,
                         enum_kind == EnumKind::Scoped ? "enumClass" : "enum");
  recorder_->EndNode();
}

void KytheGraphObserver::recordIntegerConstantNode(const NodeId &node_id,
                                                   const llvm::APSInt &Value) {
  recorder_->BeginNode(VNameFromNodeId(node_id), NodeKindID::kConstant);
  recorder_->AddProperty(PropertyID::kText, Value.toString(10));
  recorder_->EndNode();
}

void KytheGraphObserver::recordFunctionNode(const NodeId &node_id,
                                            Completeness completeness) {
  recorder_->BeginNode(VNameFromNodeId(node_id), NodeKindID::kFunction);
  recorder_->AddProperty(PropertyID::kComplete,
                         CompletenessToString(completeness));
  recorder_->EndNode();
}

void KytheGraphObserver::recordCallableNode(const NodeId &node_id) {
  recorder_->BeginNode(VNameFromNodeId(node_id), NodeKindID::kCallable);
  recorder_->EndNode();
}

void KytheGraphObserver::recordAbsNode(const NodeId &node_id) {
  recorder_->BeginNode(VNameFromNodeId(node_id), NodeKindID::kAbs);
  recorder_->EndNode();
}

void KytheGraphObserver::recordAbsVarNode(const NodeId &node_id) {
  recorder_->BeginNode(VNameFromNodeId(node_id), NodeKindID::kAbsVar);
  recorder_->EndNode();
}

void KytheGraphObserver::recordLookupNode(const NodeId &node_id,
                                          const llvm::StringRef &Name) {
  recorder_->BeginNode(VNameFromNodeId(node_id), NodeKindID::kLookup);
  recorder_->AddProperty(PropertyID::kText, Name);
  recorder_->EndNode();
}

void KytheGraphObserver::recordRecordNode(const NodeId &node_id,
                                          RecordKind kind,
                                          Completeness completeness) {
  recorder_->BeginNode(VNameFromNodeId(node_id), NodeKindID::kRecord);
  switch (kind) {
    case RecordKind::Class:
      recorder_->AddProperty(PropertyID::kSubkind, "class");
      break;
    case RecordKind::Struct:
      recorder_->AddProperty(PropertyID::kSubkind, "struct");
      break;
    case RecordKind::Union:
      recorder_->AddProperty(PropertyID::kSubkind, "union");
      break;
  };
  recorder_->AddProperty(PropertyID::kComplete,
                         CompletenessToString(completeness));
  recorder_->EndNode();
}

void KytheGraphObserver::recordTypeSpellingLocation(
    const GraphObserver::Range &type_source_range, const NodeId &type_id,
    Claimability claimability) {
  RecordAnchor(type_source_range, type_id, EdgeKindID::kRef, claimability);
}

void KytheGraphObserver::recordExtendsEdge(const NodeId &from, const NodeId &to,
                                           bool is_virtual,
                                           clang::AccessSpecifier specifier) {
  switch (specifier) {
    case clang::AccessSpecifier::AS_public:
      recorder_->AddEdge(VNameFromNodeId(from),
                         is_virtual ? EdgeKindID::kExtendsPublicVirtual
                                    : EdgeKindID::kExtendsPublic,
                         VNameFromNodeId(to));
      break;
    case clang::AccessSpecifier::AS_protected:
      recorder_->AddEdge(VNameFromNodeId(from),
                         is_virtual ? EdgeKindID::kExtendsProtectedVirtual
                                    : EdgeKindID::kExtendsProtected,
                         VNameFromNodeId(to));
      break;
    case clang::AccessSpecifier::AS_private:
      recorder_->AddEdge(VNameFromNodeId(from),
                         is_virtual ? EdgeKindID::kExtendsPrivateVirtual
                                    : EdgeKindID::kExtendsPrivate,
                         VNameFromNodeId(to));
      break;
    default:
      recorder_->AddEdge(
          VNameFromNodeId(from),
          is_virtual ? EdgeKindID::kExtendsVirtual : EdgeKindID::kExtends,
          VNameFromNodeId(to));
  }
}

void KytheGraphObserver::recordDeclUseLocation(
    const GraphObserver::Range &source_range, const NodeId &node,
    Claimability claimability) {
  RecordAnchor(source_range, node, EdgeKindID::kRef, claimability);
}

void KytheGraphObserver::pushFile(clang::SourceLocation blame_location,
                                  clang::SourceLocation source_location) {
  PreprocessorContext previous_context =
      file_stack_.empty() ? starting_context_ : file_stack_.back().context;
  bool has_previous_uid = !file_stack_.empty();
  llvm::sys::fs::UniqueID previous_uid;
  if (has_previous_uid) {
    previous_uid = file_stack_.back().uid;
  }
  file_stack_.push_back(FileState{});
  FileState &state = file_stack_.back();
  state.claimed = true;
  if (source_location.isValid()) {
    if (source_location.isMacroID()) {
      source_location = SourceManager->getExpansionLoc(source_location);
    }
    assert(source_location.isFileID());
    clang::FileID file = SourceManager->getFileID(source_location);
    if (file.isInvalid()) {
      // An actually invalid location.
    } else {
      const clang::FileEntry *entry = SourceManager->getFileEntryForID(file);
      if (entry) {
        // An actual file.
        state.vname = state.base_vname = VNameFromFileEntry(entry);
        state.uid = entry->getUniqueID();
        // Attempt to compute the state-amended VName using the state table.
        // If we aren't working under any context, we won't end up making the
        // VName more specific.
        if (file_stack_.size() == 1) {
          // Start state.
          state.context = starting_context_;
        } else if (has_previous_uid && !previous_context.empty() &&
                   blame_location.isValid() && blame_location.isFileID()) {
          unsigned offset = SourceManager->getFileOffset(blame_location);
          const auto path_info = path_to_context_data_.find(previous_uid);
          if (path_info != path_to_context_data_.end()) {
            const auto context_info = path_info->second.find(previous_context);
            if (context_info != path_info->second.end()) {
              const auto offset_info = context_info->second.find(offset);
              if (offset_info != context_info->second.end()) {
                state.context = offset_info->second;
              } else {
                fprintf(stderr,
                        "Warning: when looking for %s[%s]:%u: missing source "
                        "offset\n",
                        vfs_->get_debug_uid_string(previous_uid).c_str(),
                        previous_context.c_str(), offset);
              }
            } else {
              fprintf(stderr,
                      "Warning: when looking for %s[%s]:%u: missing source "
                      "context\n",
                      vfs_->get_debug_uid_string(previous_uid).c_str(),
                      previous_context.c_str(), offset);
            }
          } else {
            fprintf(
                stderr,
                "Warning: when looking for %s[%s]:%u: missing source path\n",
                vfs_->get_debug_uid_string(previous_uid).c_str(),
                previous_context.c_str(), offset);
          }
        }
        state.vname.set_signature(state.context + state.vname.signature());
        if (client_->Claim(claimant_, state.vname)) {
          if (recorded_files_.insert(entry).second) {
            bool was_invalid = false;
            const llvm::MemoryBuffer *buf =
                SourceManager->getMemoryBufferForFile(entry, &was_invalid);
            if (was_invalid || !buf) {
              // TODO(zarko): diagnostic logging.
            } else {
              recorder_->AddFileContent(state.base_vname, buf->getBuffer());
            }
          }
        } else {
          state.claimed = false;
        }
        KytheClaimToken token;
        token.set_vname(state.vname);
        token.set_rough_claimed(state.claimed);
        claim_checked_files_.emplace(file, token);
      } else {
        // A builtin location.
      }
    }
  }
}

void KytheGraphObserver::popFile() {
  assert(!file_stack_.empty());
  FileState state = file_stack_.back();
  file_stack_.pop_back();
  if (file_stack_.empty()) {
    RecordDeferredNodes();
  }
}

bool KytheGraphObserver::claimRange(const GraphObserver::Range &range) {
  return (range.Kind == GraphObserver::Range::RangeKind::Wraith &&
          claimNode(range.Context)) ||
         claimLocation(range.PhysicalRange.getBegin());
}

bool KytheGraphObserver::claimLocation(clang::SourceLocation source_location) {
  if (!source_location.isValid()) {
    return true;
  }
  if (source_location.isMacroID()) {
    source_location = SourceManager->getExpansionLoc(source_location);
  }
  assert(source_location.isFileID());
  clang::FileID file = SourceManager->getFileID(source_location);
  if (file.isInvalid()) {
    return true;
  }
  auto token = claim_checked_files_.find(file);
  return token != claim_checked_files_.end() ? token->second.rough_claimed()
                                             : false;
}

void KytheGraphObserver::AddContextInformation(
    const std::string &path, const PreprocessorContext &context,
    unsigned offset, const PreprocessorContext &dest_context) {
  auto found_file = vfs_->status(path);
  if (found_file) {
    path_to_context_data_[found_file->getUniqueID()][context][offset] =
        dest_context;
  } else {
    fprintf(stderr, "WARNING: Path %s could not be mapped to a VFS record.\n",
            path.c_str());
  }
}

const GraphObserver::ClaimToken *KytheGraphObserver::getClaimTokenForLocation(
    clang::SourceLocation source_location) {
  if (!source_location.isValid()) {
    return getDefaultClaimToken();
  }
  if (source_location.isMacroID()) {
    source_location = SourceManager->getExpansionLoc(source_location);
  }
  assert(source_location.isFileID());
  clang::FileID file = SourceManager->getFileID(source_location);
  if (file.isInvalid()) {
    return getDefaultClaimToken();
  }
  auto token = claim_checked_files_.find(file);
  return token != claim_checked_files_.end() ? &token->second
                                             : getDefaultClaimToken();
}

const GraphObserver::ClaimToken *KytheGraphObserver::getClaimTokenForRange(
    const clang::SourceRange &range) {
  return getClaimTokenForLocation(range.getBegin());
}

void *KytheClaimToken::clazz_ = nullptr;

}  // namespace kythe

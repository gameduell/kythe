/*
 * Copyright 2015 Google Inc. All rights reserved.
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

// Package filetree defines the filetree Service interface and a simple
// in-memory implementation.
package filetree

import (
	"fmt"
	"log"
	"net/http"
	"path/filepath"
	"time"

	"kythe.io/kythe/go/services/graphstore"
	"kythe.io/kythe/go/services/web"
	"kythe.io/kythe/go/util/kytheuri"
	"kythe.io/kythe/go/util/schema"

	"golang.org/x/net/context"

	ftpb "kythe.io/kythe/proto/filetree_proto"
	spb "kythe.io/kythe/proto/storage_proto"
)

// Service provides an interface to explore a tree of VName files.
// TODO(schroederc): add Context argument to interface methods
type Service interface {
	// Directory returns the contents of the directory at the given corpus/root/path.
	Directory(context.Context, *ftpb.DirectoryRequest) (*ftpb.DirectoryReply, error)

	// CorpusRoots returns a map from corpus to known roots.
	CorpusRoots(context.Context, *ftpb.CorpusRootsRequest) (*ftpb.CorpusRootsReply, error)
}

type grpcClient struct{ ftpb.FileTreeServiceClient }

// CorpusRoots implements part of Service interface.
func (c *grpcClient) CorpusRoots(ctx context.Context, req *ftpb.CorpusRootsRequest) (*ftpb.CorpusRootsReply, error) {
	return c.FileTreeServiceClient.CorpusRoots(ctx, req)
}

// Directory implements part of Service interface.
func (c *grpcClient) Directory(ctx context.Context, req *ftpb.DirectoryRequest) (*ftpb.DirectoryReply, error) {
	return c.FileTreeServiceClient.Directory(ctx, req)
}

// GRPC returns a filetree Service backed by a FileTreeServiceClient.
func GRPC(c ftpb.FileTreeServiceClient) Service { return &grpcClient{c} }

// Map is a FileTree backed by an in-memory map.
type Map struct {
	// corpus -> root -> dirPath -> DirectoryReply
	M map[string]map[string]map[string]*ftpb.DirectoryReply
}

// NewMap returns an empty filetree map.
func NewMap() *Map {
	return &Map{make(map[string]map[string]map[string]*ftpb.DirectoryReply)}
}

// Populate adds each file node in gs to m.
func (m *Map) Populate(ctx context.Context, gs graphstore.Service) error {
	start := time.Now()
	log.Println("Populating in-memory file tree")
	var total int
	if err := gs.Scan(ctx, &spb.ScanRequest{FactPrefix: schema.NodeKindFact},
		func(entry *spb.Entry) error {
			if entry.FactName == schema.NodeKindFact && string(entry.FactValue) == schema.FileKind {
				m.AddFile(entry.Source)
				total++
			}
			return nil
		}); err != nil {
		return fmt.Errorf("failed to Scan GraphStore for directory structure: %v", err)
	}
	log.Printf("Indexed %d files in %s", total, time.Since(start))
	return nil
}

// AddFile adds the given file VName to m.
func (m *Map) AddFile(file *spb.VName) {
	ticket := kytheuri.ToString(file)
	path := filepath.Join("/", file.Path)
	dir := m.ensureDir(file.Corpus, file.Root, filepath.Dir(path))
	dir.File = addToSet(dir.File, ticket)
}

// CorpusRoots implements part of the filetree.Service interface.
func (m *Map) CorpusRoots(ctx context.Context, req *ftpb.CorpusRootsRequest) (*ftpb.CorpusRootsReply, error) {
	cr := &ftpb.CorpusRootsReply{}
	for corpus, rootDirs := range m.M {
		var roots []string
		for root := range rootDirs {
			roots = append(roots, root)
		}
		cr.Corpus = append(cr.Corpus, &ftpb.CorpusRootsReply_Corpus{
			Name: corpus,
			Root: roots,
		})
	}
	return cr, nil
}

// Directory implements part of the filetree.Service interface.
func (m *Map) Directory(ctx context.Context, req *ftpb.DirectoryRequest) (*ftpb.DirectoryReply, error) {
	roots := m.M[req.Corpus]
	if roots == nil {
		return nil, nil
	}
	dirs := roots[req.Root]
	if dirs == nil {
		return nil, nil
	}
	return dirs[req.Path], nil
}

func (m *Map) ensureCorpusRoot(corpus, root string) map[string]*ftpb.DirectoryReply {
	roots := m.M[corpus]
	if roots == nil {
		roots = make(map[string]map[string]*ftpb.DirectoryReply)
		m.M[corpus] = roots
	}

	dirs := roots[root]
	if dirs == nil {
		dirs = make(map[string]*ftpb.DirectoryReply)
		roots[root] = dirs
	}
	return dirs
}

func (m *Map) ensureDir(corpus, root, path string) *ftpb.DirectoryReply {
	dirs := m.ensureCorpusRoot(corpus, root)
	dir := dirs[path]
	if dir == nil {
		dir = &ftpb.DirectoryReply{}
		dirs[path] = dir

		if path != "/" {
			parent := m.ensureDir(corpus, root, filepath.Dir(path))
			uri := kytheuri.URI{
				Corpus: corpus,
				Root:   root,
				Path:   path,
			}
			parent.Subdirectory = addToSet(parent.Subdirectory, uri.String())
		}
	}
	return dir
}

func addToSet(strs []string, str string) []string {
	for _, s := range strs {
		if s == str {
			return strs
		}
	}
	return append(strs, str)
}

type webClient struct{ addr string }

// CorpusRoots implements part of the Service interface.
func (w *webClient) CorpusRoots(ctx context.Context, req *ftpb.CorpusRootsRequest) (*ftpb.CorpusRootsReply, error) {
	var reply ftpb.CorpusRootsReply
	return &reply, web.Call(w.addr, "corpusRoots", req, &reply)
}

// Directory implements part of the Service interface.
func (w *webClient) Directory(ctx context.Context, req *ftpb.DirectoryRequest) (*ftpb.DirectoryReply, error) {
	var reply ftpb.DirectoryReply
	return &reply, web.Call(w.addr, "dir", req, &reply)
}

// WebClient returns an filetree Service based on a remote web server.
func WebClient(addr string) Service { return &webClient{addr} }

// RegisterHTTPHandlers registers JSON HTTP handlers with mux using the given
// filetree Service.  The following methods with be exposed:
//
//   GET /corpusRoots
//     Response: JSON encoded filetree.CorpusRootsReply
//   GET /dir
//     Request: JSON encoded filetree.DirectoryRequest
//     Response: JSON encoded filetree.DirectoryReply
//
// Note: /corpusRoots and /dir will return their responses as serialized
// protobufs if the "proto" query parameter is set.
func RegisterHTTPHandlers(ctx context.Context, ft Service, mux *http.ServeMux) {
	mux.HandleFunc("/corpusRoots", func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		defer func() {
			log.Printf("filetree.CorpusRoots:\t%s", time.Since(start))
		}()

		var req ftpb.CorpusRootsRequest
		if err := web.ReadJSONBody(r, &req); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		cr, err := ft.CorpusRoots(ctx, &req)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		if err := web.WriteResponse(w, r, cr); err != nil {
			log.Println(err)
		}
	})
	mux.HandleFunc("/dir", func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		defer func() {
			log.Printf("filetree.Dir:\t%s", time.Since(start))
		}()

		var req ftpb.DirectoryRequest
		if err := web.ReadJSONBody(r, &req); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		reply, err := ft.Directory(ctx, &req)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		if err := web.WriteResponse(w, r, reply); err != nil {
			log.Println(err)
		}
	})
}

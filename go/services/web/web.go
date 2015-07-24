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

// Package web defines utility functions for exposing services over HTTP.
package web

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io/ioutil"
	"net/http"
	"os"
	"strings"

	"kythe.io/kythe/go/util/httpencoding"

	"github.com/golang/protobuf/proto"
)

const jsonBodyType = "application/json; charset=utf-8"

// RegisterQuitHandler adds a handler for /quitquitquit that call os.Exit(0).
func RegisterQuitHandler(mux *http.ServeMux) {
	mux.HandleFunc("/quitquitquit", func(w http.ResponseWriter, r *http.Request) {
		os.Exit(0)
	})
}

// Call sends req to the given server method as a JSON-encoded body and
// unmarshals the response body as JSON into reply.
func Call(server, method string, req, reply interface{}) error {
	body, err := json.Marshal(req)
	if err != nil {
		return fmt.Errorf("error marshaling %T: %v", req, err)
	}
	resp, err := http.Post(strings.TrimSuffix(server, "/")+"/"+strings.Trim(method, "/"),
		jsonBodyType, bytes.NewBuffer(body))
	if err != nil {
		return fmt.Errorf("http error: %v", err)
	}
	rec, err := ioutil.ReadAll(resp.Body)
	resp.Body.Close()
	if err != nil {
		return fmt.Errorf("error reading response body: %v", err)
	} else if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("remote method error (code %d): %s", resp.StatusCode, string(rec))
	}
	if err := json.Unmarshal(rec, &reply); err != nil {
		return fmt.Errorf("error unmarshaling %T: %v", reply, err)
	}
	return nil
}

// ReadJSONBody reads the entire body of r and unmarshals it from JSON into v.
func ReadJSONBody(r *http.Request, v interface{}) error {
	rec, err := ioutil.ReadAll(r.Body)
	if err != nil {
		return fmt.Errorf("body read error: %v", err)
	}
	return json.Unmarshal(rec, v)
}

// WriteResponse writes msg to w as a serialized protobuf if the "proto" query
// parameter is set; otherwise as JSON.
func WriteResponse(w http.ResponseWriter, r *http.Request, msg proto.Message) error {
	if Arg(r, "proto") != "" {
		return WriteProtoResponse(w, r, msg)
	}
	return WriteJSONResponse(w, r, msg)
}

// WriteJSONResponse encodes v as JSON and writes it to w.
func WriteJSONResponse(w http.ResponseWriter, r *http.Request, v interface{}) error {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	cw := httpencoding.CompressData(w, r)
	defer cw.Close()
	return json.NewEncoder(cw).Encode(v)
}

// WriteProtoResponse serializes msg to w.
func WriteProtoResponse(w http.ResponseWriter, r *http.Request, msg proto.Message) error {
	w.Header().Set("Content-Type", "application/x-protobuf")
	cw := httpencoding.CompressData(w, r)
	defer cw.Close()
	rec, err := proto.Marshal(msg)
	if err != nil {
		return fmt.Errorf("error marshaling proto: %v", err)
	}
	_, err = cw.Write(rec)
	return err
}

// Arg returns the first query value for the named parameter or "" if it was not
// set.
func Arg(r *http.Request, name string) string {
	args := r.URL.Query()[name]
	if len(args) == 0 {
		return ""
	}
	return args[0]
}

// ArgOrNil returns a pointer to first query value for the named parameter or
// nil if it was not set.
func ArgOrNil(r *http.Request, name string) *string {
	arg := Arg(r, name)
	if arg == "" {
		return nil
	}
	return &arg
}

// Code generated by protoc-gen-go.
// source: kythe/proto/filetree.proto
// DO NOT EDIT!

/*
Package filetree_proto is a generated protocol buffer package.

It is generated from these files:
	kythe/proto/filetree.proto

It has these top-level messages:
	CorpusRootsRequest
	CorpusRootsReply
	DirectoryRequest
	DirectoryReply
*/
package filetree_proto

import proto "github.com/golang/protobuf/proto"

import (
	context "golang.org/x/net/context"
	grpc "google.golang.org/grpc"
)

// Reference imports to suppress errors if they are not otherwise used.
var _ context.Context
var _ grpc.ClientConn

// Reference imports to suppress errors if they are not otherwise used.
var _ = proto.Marshal

type CorpusRootsRequest struct {
}

func (m *CorpusRootsRequest) Reset()         { *m = CorpusRootsRequest{} }
func (m *CorpusRootsRequest) String() string { return proto.CompactTextString(m) }
func (*CorpusRootsRequest) ProtoMessage()    {}

type CorpusRootsReply struct {
	Corpus []*CorpusRootsReply_Corpus `protobuf:"bytes,1,rep,name=corpus" json:"corpus,omitempty"`
}

func (m *CorpusRootsReply) Reset()         { *m = CorpusRootsReply{} }
func (m *CorpusRootsReply) String() string { return proto.CompactTextString(m) }
func (*CorpusRootsReply) ProtoMessage()    {}

func (m *CorpusRootsReply) GetCorpus() []*CorpusRootsReply_Corpus {
	if m != nil {
		return m.Corpus
	}
	return nil
}

type CorpusRootsReply_Corpus struct {
	Name string   `protobuf:"bytes,1,opt,name=name" json:"name,omitempty"`
	Root []string `protobuf:"bytes,2,rep,name=root" json:"root,omitempty"`
}

func (m *CorpusRootsReply_Corpus) Reset()         { *m = CorpusRootsReply_Corpus{} }
func (m *CorpusRootsReply_Corpus) String() string { return proto.CompactTextString(m) }
func (*CorpusRootsReply_Corpus) ProtoMessage()    {}

type DirectoryRequest struct {
	Corpus string `protobuf:"bytes,1,opt,name=corpus" json:"corpus,omitempty"`
	Root   string `protobuf:"bytes,2,opt,name=root" json:"root,omitempty"`
	Path   string `protobuf:"bytes,3,opt,name=path" json:"path,omitempty"`
}

func (m *DirectoryRequest) Reset()         { *m = DirectoryRequest{} }
func (m *DirectoryRequest) String() string { return proto.CompactTextString(m) }
func (*DirectoryRequest) ProtoMessage()    {}

type DirectoryReply struct {
	// Set of tickets for each contained sub-directory's corpus, root, and path.
	Subdirectory []string `protobuf:"bytes,1,rep,name=subdirectory" json:"subdirectory,omitempty"`
	// Set of file tickets contained within this directory.
	File []string `protobuf:"bytes,2,rep,name=file" json:"file,omitempty"`
}

func (m *DirectoryReply) Reset()         { *m = DirectoryReply{} }
func (m *DirectoryReply) String() string { return proto.CompactTextString(m) }
func (*DirectoryReply) ProtoMessage()    {}

func init() {
}

// Client API for FileTreeService service

type FileTreeServiceClient interface {
	// CorpusRoots returns all known corpus/root pairs for stored files.
	CorpusRoots(ctx context.Context, in *CorpusRootsRequest, opts ...grpc.CallOption) (*CorpusRootsReply, error)
	// Directory returns the file/sub-directory contents of the given directory.
	Directory(ctx context.Context, in *DirectoryRequest, opts ...grpc.CallOption) (*DirectoryReply, error)
}

type fileTreeServiceClient struct {
	cc *grpc.ClientConn
}

func NewFileTreeServiceClient(cc *grpc.ClientConn) FileTreeServiceClient {
	return &fileTreeServiceClient{cc}
}

func (c *fileTreeServiceClient) CorpusRoots(ctx context.Context, in *CorpusRootsRequest, opts ...grpc.CallOption) (*CorpusRootsReply, error) {
	out := new(CorpusRootsReply)
	err := grpc.Invoke(ctx, "/kythe.proto.FileTreeService/CorpusRoots", in, out, c.cc, opts...)
	if err != nil {
		return nil, err
	}
	return out, nil
}

func (c *fileTreeServiceClient) Directory(ctx context.Context, in *DirectoryRequest, opts ...grpc.CallOption) (*DirectoryReply, error) {
	out := new(DirectoryReply)
	err := grpc.Invoke(ctx, "/kythe.proto.FileTreeService/Directory", in, out, c.cc, opts...)
	if err != nil {
		return nil, err
	}
	return out, nil
}

// Server API for FileTreeService service

type FileTreeServiceServer interface {
	// CorpusRoots returns all known corpus/root pairs for stored files.
	CorpusRoots(context.Context, *CorpusRootsRequest) (*CorpusRootsReply, error)
	// Directory returns the file/sub-directory contents of the given directory.
	Directory(context.Context, *DirectoryRequest) (*DirectoryReply, error)
}

func RegisterFileTreeServiceServer(s *grpc.Server, srv FileTreeServiceServer) {
	s.RegisterService(&_FileTreeService_serviceDesc, srv)
}

func _FileTreeService_CorpusRoots_Handler(srv interface{}, ctx context.Context, buf []byte) (proto.Message, error) {
	in := new(CorpusRootsRequest)
	if err := proto.Unmarshal(buf, in); err != nil {
		return nil, err
	}
	out, err := srv.(FileTreeServiceServer).CorpusRoots(ctx, in)
	if err != nil {
		return nil, err
	}
	return out, nil
}

func _FileTreeService_Directory_Handler(srv interface{}, ctx context.Context, buf []byte) (proto.Message, error) {
	in := new(DirectoryRequest)
	if err := proto.Unmarshal(buf, in); err != nil {
		return nil, err
	}
	out, err := srv.(FileTreeServiceServer).Directory(ctx, in)
	if err != nil {
		return nil, err
	}
	return out, nil
}

var _FileTreeService_serviceDesc = grpc.ServiceDesc{
	ServiceName: "kythe.proto.FileTreeService",
	HandlerType: (*FileTreeServiceServer)(nil),
	Methods: []grpc.MethodDesc{
		{
			MethodName: "CorpusRoots",
			Handler:    _FileTreeService_CorpusRoots_Handler,
		},
		{
			MethodName: "Directory",
			Handler:    _FileTreeService_Directory_Handler,
		},
	},
	Streams: []grpc.StreamDesc{},
}

package main

type OrderStore struct{}

func (s *OrderStore) Find(id int) {}

type Server struct {
	store *OrderStore
}

func (s *Server) Start() string {
	s.store.Find(1)
	return "up"
}

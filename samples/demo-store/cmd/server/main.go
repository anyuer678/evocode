package main

import "fmt"

func main() {
	s := &Server{store: &OrderStore{}}
	fmt.Println(s.Start())
}

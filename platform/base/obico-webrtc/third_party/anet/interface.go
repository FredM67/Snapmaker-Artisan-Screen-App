// Package anet is a narrow compatibility shim for Pion on Artisan's Android
// 10 firmware. Upstream anet's Android 11+ workaround references removed Go
// internals, but uses precisely these stdlib calls on Android 10.
package anet

import "net"

func Interfaces() ([]net.Interface, error) { return net.Interfaces() }

func InterfaceAddrs() ([]net.Addr, error) { return net.InterfaceAddrs() }

func InterfaceAddrsByInterface(ifi *net.Interface) ([]net.Addr, error) {
	return ifi.Addrs()
}

func InterfaceByIndex(index int) (*net.Interface, error) { return net.InterfaceByIndex(index) }

func InterfaceByName(name string) (*net.Interface, error) { return net.InterfaceByName(name) }

func SetAndroidVersion(_ uint) {}

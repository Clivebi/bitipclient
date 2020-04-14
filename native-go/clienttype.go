package bitipclient

type ClientConfig struct {
	UserName string   `json:"username"`
	Password string   `json:"password"`
	DNS      []string `json:"dns"`
	Exclude  []string `json:"exclude"`
}

type Connector interface {
	IsWorking() bool
	Connect(addr string) error
	Close()
}
